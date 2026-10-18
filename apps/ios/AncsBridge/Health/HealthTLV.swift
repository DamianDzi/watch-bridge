import Foundation

/// TLV codec for the health bridge. Byte layout is pinned in
/// specs/health-ble-protocol.md — the watch side (apps/wearos) must match
/// this exactly. All integers little-endian.
///
/// batch  = [u8 count] record+
/// record = [u8 type][u16 len][payload]
/// payload always starts with [u64 record_id] (Room autoincrement on the
/// watch, monotonic per app install) followed by type-specific fields.
enum HealthRecordType: UInt8, CaseIterable {
    case heartRate = 1
    case steps = 2
    case sleep = 3
    case sleepSummary = 4
    case workoutHeader = 5
    case workoutHrStream = 6
    case workoutGps = 7
    case battery = 8
}

// MARK: - Record payloads

struct GpsPoint {
    let latitudeE7: Int32   // degrees × 1e7
    let longitudeE7: Int32  // degrees × 1e7
    let altitudeMeters: Float32
    let timestampMs: Int64
}

struct WorkoutHeader {
    let activityType: UInt8         // HealthWorkoutType table in specs/health-ble-protocol.md
    let startMs: Int64
    let endMs: Int64
    let activeMinutes: UInt32
    let caloriesTenths: UInt16      // kcal × 10
    let distanceMeters: UInt32
    let avgHeartRate: UInt16        // bpm, 0 = unavailable
    let maxHeartRate: UInt16        // bpm, 0 = unavailable
    let hrSampleCount: UInt16       // WORKOUT_HR_STREAM records expected after this header
    let gpsPointCount: UInt16       // WORKOUT_GPS records expected after this header
}

struct SleepStageDuration {
    let stage: UInt8       // HealthSleepStage table
    let minutes: UInt32
}

enum HealthRecordPayload {
    case heartRate(bpm: UInt16, startMs: Int64)
    case steps(count: UInt32, startMs: Int64, endMs: Int64)
    case sleep(stage: UInt8, startMs: Int64, endMs: Int64)
    case sleepSummary(startMs: Int64, endMs: Int64, stages: [SleepStageDuration])
    case workoutHeader(WorkoutHeader)
    case workoutHrStream(samples: [(timestampMs: Int64, bpm: UInt16)])
    case workoutGps(points: [GpsPoint])
    case battery(percent: UInt8, timestampMs: Int64)
}

struct HealthRecord {
    let recordId: Int64
    let type: HealthRecordType
    let payload: HealthRecordPayload
}

// MARK: - Control messages (health-control characteristic, B5)

enum HealthControlCommand: UInt8 {
    case hello = 1            // watch → iOS: handshake
    case ack = 2              // iOS → watch: record ids confirmed saved
    case timeSync = 3         // iOS → watch: epoch ms
    case ping = 4             // watch → iOS
    case pong = 5             // iOS → watch
    case triggerList = 6      // iOS → watch: full trigger list
    case triggerFire = 7      // iOS → watch: execute stored trigger by index
    case triggerResult = 8    // watch → iOS: execution outcome
    case appListReq = 9       // iOS → watch: request the launchable-app list
    case appList = 10         // watch → iOS: one paged chunk of that list
}

enum HealthControlMessage {
    case hello(installNonce: UInt32, typeBitmap: UInt8, cursorRecordId: Int64)
    case ack(recordIds: [Int64])
    case timeSync(epochMs: Int64)
    case ping
    case pong
    case triggerList(triggers: [RemoteTrigger])
    case triggerFire(index: UInt8)
    case triggerResult(index: UInt8, result: UInt8)
    case appListReq
    case appList(chunkIndex: UInt8, isFinal: Bool, entries: [RemoteAppInfo])
}

/// One stored remote trigger (protocol §Remote triggers). `index` is the
/// watch-side execution handle; payloads are only ever sent in TRIGGER_LIST,
/// never with TRIGGER_FIRE.
struct RemoteTrigger: Equatable, Codable {
    let index: UInt8
    /// 0 = launch Android package, 1 = deep link URI (see HealthControlCodec docs)
    let kind: UInt8
    let payload: String
}

enum RemoteTriggerResult: UInt8 {
    case ok = 0
    case noSuchTrigger = 1
    case launchFailed = 2
    case blockedBySystem = 3
}

/// One launchable app on the watch (APP_LIST entries; protocol §Watch app
/// list) — the picker source for new package triggers.
struct RemoteAppInfo: Equatable, Codable {
    let packageName: String
    let label: String
}

/// Reassembles paged APP_LIST chunks. Chunk 0 starts a fresh assembly; a
/// chunk whose index is not expected+1 resets the partial list; `isFinal`
/// flushes it. Pure value type — unit-tested in HealthTLVTests.
struct AppListAssembler {
    enum FeedResult: Equatable {
        case accumulated
        case complete([RemoteAppInfo])
        case reset
    }

    private(set) var entries: [RemoteAppInfo] = []
    private var nextIndex: UInt8 = 0
    private var active = false

    mutating func feed(chunkIndex: UInt8, isFinal: Bool, entries chunkEntries: [RemoteAppInfo]) -> FeedResult {
        let expected: UInt8 = active ? nextIndex : 0
        guard chunkIndex == expected else {
            reset()
            return .reset
        }
        entries.append(contentsOf: chunkEntries)
        if isFinal {
            let result = entries
            reset()
            return .complete(result)
        }
        active = true
        nextIndex = chunkIndex &+ 1
        return .accumulated
    }

    private mutating func reset() {
        entries = []
        nextIndex = 0
        active = false
    }
}

// MARK: - Errors

enum HealthTLVError: Error, Equatable {
    case emptyBatch
    case zeroCount
    case truncatedHeader(at: Int)
    case truncatedPayload(type: UInt8, expected: Int, actual: Int)
    case countMismatch(declared: Int, decoded: Int)
    case unknownRecordType(UInt8)
    case badControlMessage
}

// MARK: - Byte reader (little-endian)

private struct ByteReader {
    let data: Data
    var offset: Int = 0

    var remaining: Int { data.count - offset }

    mutating func readU8() throws -> UInt8 {
        guard remaining >= 1 else { throw HealthTLVError.truncatedHeader(at: offset) }
        defer { offset += 1 }
        return data[data.startIndex + offset]
    }

    mutating func readU16() throws -> UInt16 {
        guard remaining >= 2 else { throw HealthTLVError.truncatedHeader(at: offset) }
        defer { offset += 2 }
        let i = data.startIndex + offset
        return UInt16(data[i]) | (UInt16(data[i + 1]) << 8)
    }

    mutating func readU32() throws -> UInt32 {
        guard remaining >= 4 else { throw HealthTLVError.truncatedHeader(at: offset) }
        defer { offset += 4 }
        let i = data.startIndex + offset
        var v: UInt32 = 0
        for b in 0..<4 { v |= UInt32(data[i + b]) << (8 * b) }
        return v
    }

    mutating func readU64() throws -> UInt64 {
        guard remaining >= 8 else { throw HealthTLVError.truncatedHeader(at: offset) }
        defer { offset += 8 }
        let i = data.startIndex + offset
        var v: UInt64 = 0
        for b in 0..<8 { v |= UInt64(data[i + b]) << (8 * b) }
        return v
    }

    mutating func readI32() throws -> Int32 {
        try Int32(bitPattern: readU32())
    }

    mutating func readF32() throws -> Float32 {
        try Float32(bitPattern: readU32())
    }

    mutating func readBytes(_ count: Int) throws -> Data {
        guard remaining >= count else { throw HealthTLVError.truncatedHeader(at: offset) }
        let start = data.startIndex + offset
        defer { offset += count }
        return data.subdata(in: start..<(start + count))
    }
}

private struct ByteWriter {
    var data = Data()

    mutating func writeU8(_ v: UInt8) { data.append(v) }

    mutating func writeU16(_ v: UInt16) {
        data.append(UInt8(v & 0xFF))
        data.append(UInt8((v >> 8) & 0xFF))
    }

    mutating func writeU32(_ v: UInt32) {
        for b in 0..<4 { data.append(UInt8((v >> (8 * b)) & 0xFF)) }
    }

    mutating func writeU64(_ v: UInt64) {
        for b in 0..<8 { data.append(UInt8((v >> (8 * b)) & 0xFF)) }
    }

    mutating func writeI32(_ v: Int32) { writeU32(UInt32(bitPattern: v)) }

    mutating func writeF32(_ v: Float32) { writeU32(v.bitPattern) }

    mutating func writeUTF8(_ s: String) {
        let bytes = Array(s.utf8)
        writeU16(UInt16(bytes.count))
        data.append(contentsOf: bytes)
    }
}

// MARK: - Batch decoding

enum HealthTLVDecoder {

    static func decodeBatch(_ data: Data) throws -> [HealthRecord] {
        guard !data.isEmpty else { throw HealthTLVError.emptyBatch }
        var reader = ByteReader(data: data)
        let declaredCount = try reader.readU8()
        guard declaredCount > 0 else { throw HealthTLVError.zeroCount }

        var records: [HealthRecord] = []
        for _ in 0..<declaredCount {
            let rawType = try reader.readU8()
            let length = Int(try reader.readU16())
            guard reader.remaining >= length else {
                throw HealthTLVError.truncatedPayload(type: rawType, expected: length, actual: reader.remaining)
            }
            let payloadData = data.subdata(in: (data.startIndex + reader.offset)..<(data.startIndex + reader.offset + length))
            reader.offset += length

            guard let type = HealthRecordType(rawValue: rawType) else {
                throw HealthTLVError.unknownRecordType(rawType)
            }
            records.append(try decodeRecord(type: type, payload: payloadData))
        }

        guard records.count == declaredCount else {
            throw HealthTLVError.countMismatch(declared: Int(declaredCount), decoded: records.count)
        }
        return records
    }

    private static func decodeRecord(type: HealthRecordType, payload: Data) throws -> HealthRecord {
        var r = ByteReader(data: payload)
        let recordId = Int64(bitPattern: try r.readU64())

        let parsed: HealthRecordPayload
        switch type {
        case .heartRate:
            let bpm = try r.readU16()
            let startMs = Int64(bitPattern: try r.readU64())
            parsed = .heartRate(bpm: bpm, startMs: startMs)
        case .steps:
            let count = try r.readU32()
            let startMs = Int64(bitPattern: try r.readU64())
            let endMs = Int64(bitPattern: try r.readU64())
            parsed = .steps(count: count, startMs: startMs, endMs: endMs)
        case .sleep:
            let stage = try r.readU8()
            let startMs = Int64(bitPattern: try r.readU64())
            let endMs = Int64(bitPattern: try r.readU64())
            parsed = .sleep(stage: stage, startMs: startMs, endMs: endMs)
        case .sleepSummary:
            let startMs = Int64(bitPattern: try r.readU64())
            let endMs = Int64(bitPattern: try r.readU64())
            let stageCount = try r.readU8()
            var stages: [SleepStageDuration] = []
            for _ in 0..<stageCount {
                stages.append(SleepStageDuration(stage: try r.readU8(), minutes: try r.readU32()))
            }
            parsed = .sleepSummary(startMs: startMs, endMs: endMs, stages: stages)
        case .workoutHeader:
            parsed = .workoutHeader(WorkoutHeader(
                activityType: try r.readU8(),
                startMs: Int64(bitPattern: try r.readU64()),
                endMs: Int64(bitPattern: try r.readU64()),
                activeMinutes: try r.readU32(),
                caloriesTenths: try r.readU16(),
                distanceMeters: try r.readU32(),
                avgHeartRate: try r.readU16(),
                maxHeartRate: try r.readU16(),
                hrSampleCount: try r.readU16(),
                gpsPointCount: try r.readU16()
            ))
        case .workoutHrStream:
            let sampleCount = try r.readU8()
            var samples: [(timestampMs: Int64, bpm: UInt16)] = []
            for _ in 0..<sampleCount {
                samples.append((Int64(bitPattern: try r.readU64()), try r.readU16()))
            }
            parsed = .workoutHrStream(samples: samples)
        case .workoutGps:
            let pointCount = try r.readU8()
            var points: [GpsPoint] = []
            for _ in 0..<pointCount {
                points.append(GpsPoint(
                    latitudeE7: try r.readI32(),
                    longitudeE7: try r.readI32(),
                    altitudeMeters: try r.readF32(),
                    timestampMs: Int64(bitPattern: try r.readU64())
                ))
            }
            parsed = .workoutGps(points: points)
        case .battery:
            let percent = try r.readU8()
            let timestampMs = Int64(bitPattern: try r.readU64())
            parsed = .battery(percent: percent, timestampMs: timestampMs)
        }
        return HealthRecord(recordId: recordId, type: type, payload: parsed)
    }
}

// MARK: - Control encode/decode

enum HealthControlCodec {

    static func encode(_ message: HealthControlMessage) -> Data {
        var w = ByteWriter()
        switch message {
        case .hello(let nonce, let bitmap, let cursor):
            w.writeU8(HealthControlCommand.hello.rawValue)
            w.writeU32(nonce)
            w.writeU8(bitmap)
            w.writeU64(UInt64(bitPattern: cursor))
        case .ack(let ids):
            w.writeU8(HealthControlCommand.ack.rawValue)
            w.writeU8(UInt8(ids.count))
            for id in ids { w.writeU64(UInt64(bitPattern: id)) }
        case .timeSync(let epochMs):
            w.writeU8(HealthControlCommand.timeSync.rawValue)
            w.writeU64(UInt64(bitPattern: epochMs))
        case .ping:
            w.writeU8(HealthControlCommand.ping.rawValue)
        case .pong:
            w.writeU8(HealthControlCommand.pong.rawValue)
        case .triggerList(let triggers):
            w.writeU8(HealthControlCommand.triggerList.rawValue)
            w.writeU8(UInt8(triggers.count))
            for trigger in triggers {
                w.writeU8(trigger.index)
                w.writeU8(trigger.kind)
                w.writeUTF8(trigger.payload)
            }
        case .triggerFire(let index):
            w.writeU8(HealthControlCommand.triggerFire.rawValue)
            w.writeU8(index)
        case .triggerResult(let index, let result):
            w.writeU8(HealthControlCommand.triggerResult.rawValue)
            w.writeU8(index)
            w.writeU8(result)
        case .appListReq:
            w.writeU8(HealthControlCommand.appListReq.rawValue)
        case .appList(let chunkIndex, let isFinal, let entries):
            w.writeU8(HealthControlCommand.appList.rawValue)
            w.writeU8(chunkIndex)
            w.writeU8(isFinal ? 1 : 0)
            for app in entries {
                w.writeUTF8(app.packageName)
                w.writeUTF8(app.label)
            }
        }
        return w.data
    }

    static func decode(_ data: Data) throws -> HealthControlMessage {
        var r = ByteReader(data: data)
        guard let raw = try? r.readU8(), let command = HealthControlCommand(rawValue: raw) else {
            throw HealthTLVError.badControlMessage
        }
        switch command {
        case .hello:
            let nonce = try r.readU32()
            let bitmap = try r.readU8()
            let cursor = Int64(bitPattern: try r.readU64())
            return .hello(installNonce: nonce, typeBitmap: bitmap, cursorRecordId: cursor)
        case .ack:
            let count = try r.readU8()
            var ids: [Int64] = []
            for _ in 0..<count { ids.append(Int64(bitPattern: try r.readU64())) }
            return .ack(recordIds: ids)
        case .timeSync:
            return .timeSync(epochMs: Int64(bitPattern: try r.readU64()))
        case .ping:
            return .ping
        case .pong:
            return .pong
        case .triggerList:
            let count = try r.readU8()
            var triggers: [RemoteTrigger] = []
            for _ in 0..<count {
                let index = try r.readU8()
                let kind = try r.readU8()
                let len = Int(try r.readU16())
                guard let payload = String(data: try r.readBytes(len), encoding: .utf8) else {
                    throw HealthTLVError.badControlMessage
                }
                triggers.append(RemoteTrigger(index: index, kind: kind, payload: payload))
            }
            return .triggerList(triggers: triggers)
        case .triggerFire:
            return .triggerFire(index: try r.readU8())
        case .triggerResult:
            return .triggerResult(index: try r.readU8(), result: try r.readU8())
        case .appListReq:
            // Empty body only — anything else is a malformed message.
            guard r.remaining == 0 else { throw HealthTLVError.badControlMessage }
            return .appListReq
        case .appList:
            let chunkIndex = try r.readU8()
            let finalRaw = try r.readU8()
            guard finalRaw <= 1 else { throw HealthTLVError.badControlMessage }
            var entries: [RemoteAppInfo] = []
            while r.remaining > 0 {
                let pkgLen = Int(try r.readU16())
                guard let pkg = String(data: try r.readBytes(pkgLen), encoding: .utf8) else {
                    throw HealthTLVError.badControlMessage
                }
                let labelLen = Int(try r.readU16())
                guard let label = String(data: try r.readBytes(labelLen), encoding: .utf8) else {
                    throw HealthTLVError.badControlMessage
                }
                entries.append(RemoteAppInfo(packageName: pkg, label: label))
            }
            return .appList(chunkIndex: chunkIndex, isFinal: finalRaw == 1, entries: entries)
        }
        if r.remaining != 0 { throw HealthTLVError.badControlMessage }
    }
}
