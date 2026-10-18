import XCTest
@testable import AncsBridge

/// Byte-level tests for the health bridge TLV codec. Bytes are constructed
/// by hand (not via a shared encoder) so a framing regression on either side
/// of the wire fails loudly here.
final class HealthTLVTests: XCTestCase {

    private struct TestWriter {
        var data = Data()
        mutating func u8(_ v: UInt8) { data.append(v) }
        mutating func u16(_ v: UInt16) { for b in 0..<2 { data.append(UInt8((v >> (8 * b)) & 0xFF)) } }
        mutating func u32(_ v: UInt32) { for b in 0..<4 { data.append(UInt8((v >> (8 * b)) & 0xFF)) } }
        mutating func u64(_ v: UInt64) { for b in 0..<8 { data.append(UInt8((v >> (8 * b)) & 0xFF)) } }
        mutating func i32(_ v: Int32) { u32(UInt32(bitPattern: v)) }
        mutating func f32(_ v: Float32) { u32(v.bitPattern) }
    }

    private func batch(_ recordCount: UInt8, _ buildRecords: (inout TestWriter) -> Void) -> Data {
        var w = TestWriter()
        w.u8(recordCount)
        buildRecords(&w)
        return w.data
    }

    private func record(_ w: inout TestWriter, type: UInt8, id: Int64, payload: (inout TestWriter) -> Void) {
        var pw = TestWriter()
        pw.u64(UInt64(bitPattern: id))
        payload(&pw)
        w.u8(type)
        w.u16(UInt16(pw.data.count))
        w.data.append(pw.data)
    }

    // MARK: - Record decoding

    func testDecodeHeartRate() throws {
        let data = batch(1) { w in
            record(&w, type: 1, id: 42) { p in
                p.u16(72)
                p.u64(1_728_000_000_000)
            }
        }
        let records = try HealthTLVDecoder.decodeBatch(data)
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records[0].recordId, 42)
        XCTAssertEqual(records[0].type, .heartRate)
        guard case .heartRate(let bpm, let startMs) = records[0].payload else { return XCTFail("wrong payload") }
        XCTAssertEqual(bpm, 72)
        XCTAssertEqual(startMs, 1_728_000_000_000)
    }

    func testDecodeSteps() throws {
        let data = batch(1) { w in
            record(&w, type: 2, id: 7) { p in
                p.u32(8_500)
                p.u64(100)
                p.u64(200)
            }
        }
        let records = try HealthTLVDecoder.decodeBatch(data)
        guard case .steps(let count, let startMs, let endMs) = try XCTUnwrap(records.first).payload else { return XCTFail() }
        XCTAssertEqual(count, 8_500)
        XCTAssertEqual(startMs, 100)
        XCTAssertEqual(endMs, 200)
    }

    func testDecodeSleep() throws {
        let data = batch(1) { w in
            record(&w, type: 3, id: 9) { p in
                p.u8(2)  // deep
                p.u64(100)
                p.u64(4600)
            }
        }
        let records = try HealthTLVDecoder.decodeBatch(data)
        guard case .sleep(let stage, let startMs, let endMs) = try XCTUnwrap(records.first).payload else { return XCTFail() }
        XCTAssertEqual(stage, 2)
        XCTAssertEqual(startMs, 100)
        XCTAssertEqual(endMs, 4600)
    }

    func testDecodeSleepSummary() throws {
        let data = batch(1) { w in
            record(&w, type: 4, id: 10) { p in
                p.u64(0)
                p.u64(28_800_000)
                p.u8(3)
                p.u8(1); p.u32(320)  // light 320 min
                p.u8(2); p.u32(90)   // deep 90 min
                p.u8(3); p.u32(60)   // REM 60 min
            }
        }
        let records = try HealthTLVDecoder.decodeBatch(data)
        guard case .sleepSummary(let startMs, let endMs, let stages) = try XCTUnwrap(records.first).payload else { return XCTFail() }
        XCTAssertEqual(startMs, 0)
        XCTAssertEqual(endMs, 28_800_000)
        XCTAssertEqual(stages.map(\.stage), [1, 2, 3])
        XCTAssertEqual(stages.map(\.minutes), [320, 90, 60])
    }

    func testDecodeWorkoutHeader() throws {
        let data = batch(1) { w in
            record(&w, type: 5, id: 11) { p in
                p.u8(0)          // running
                p.u64(1000)
                p.u64(1_900_000)
                p.u32(30)        // active minutes
                p.u16(2_450)     // 245.0 kcal
                p.u32(5_200)     // meters
                p.u16(150)
                p.u16(178)
                p.u16(240)       // 240 HR stream records expected
                p.u16(900)       // 900 GPS records expected
            }
        }
        let records = try HealthTLVDecoder.decodeBatch(data)
        guard case .workoutHeader(let header) = try XCTUnwrap(records.first).payload else { return XCTFail() }
        XCTAssertEqual(header.activityType, 0)
        XCTAssertEqual(header.startMs, 1000)
        XCTAssertEqual(header.endMs, 1_900_000)
        XCTAssertEqual(header.activeMinutes, 30)
        XCTAssertEqual(header.caloriesTenths, 2_450)
        XCTAssertEqual(header.distanceMeters, 5_200)
        XCTAssertEqual(header.avgHeartRate, 150)
        XCTAssertEqual(header.maxHeartRate, 178)
        XCTAssertEqual(header.hrSampleCount, 240)
        XCTAssertEqual(header.gpsPointCount, 900)
    }

    func testDecodeWorkoutHrStream() throws {
        let data = batch(1) { w in
            record(&w, type: 6, id: 12) { p in
                p.u8(2)
                p.u64(5_000); p.u16(120)
                p.u64(5_100); p.u16(124)
            }
        }
        let records = try HealthTLVDecoder.decodeBatch(data)
        guard case .workoutHrStream(let samples) = try XCTUnwrap(records.first).payload else { return XCTFail() }
        XCTAssertEqual(samples.count, 2)
        XCTAssertEqual(samples[0].timestampMs, 5_000)
        XCTAssertEqual(samples[0].bpm, 120)
        XCTAssertEqual(samples[1].bpm, 124)
    }

    func testDecodeWorkoutGps() throws {
        let data = batch(1) { w in
            record(&w, type: 7, id: 13) { p in
                p.u8(1)
                p.i32(5_250_000_00)   // 52.5 N
                p.i32(1_310_000_00)   // 13.1 E
                p.f32(42.5)
                p.u64(9_000)
            }
        }
        let records = try HealthTLVDecoder.decodeBatch(data)
        guard case .workoutGps(let points) = try XCTUnwrap(records.first).payload else { return XCTFail() }
        XCTAssertEqual(points.count, 1)
        XCTAssertEqual(points[0].latitudeE7, 5_250_000_00)
        XCTAssertEqual(points[0].longitudeE7, 1_310_000_00)
        XCTAssertEqual(points[0].altitudeMeters, 42.5)
        XCTAssertEqual(points[0].timestampMs, 9_000)
    }

    func testDecodeBattery() throws {
        let data = batch(1) { w in
            record(&w, type: 8, id: 14) { p in
                p.u8(83)
                p.u64(777)
            }
        }
        let records = try HealthTLVDecoder.decodeBatch(data)
        guard case .battery(let percent, let timestampMs) = try XCTUnwrap(records.first).payload else { return XCTFail() }
        XCTAssertEqual(percent, 83)
        XCTAssertEqual(timestampMs, 777)
    }

    func testDecodeMultiRecordBatch() throws {
        let data = batch(3) { w in
            record(&w, type: 1, id: 1) { p in p.u16(60); p.u64(10) }
            record(&w, type: 2, id: 2) { p in p.u32(100); p.u64(0); p.u64(50) }
            record(&w, type: 8, id: 3) { p in p.u8(90); p.u64(20) }
        }
        let records = try HealthTLVDecoder.decodeBatch(data)
        XCTAssertEqual(records.map(\.recordId), [1, 2, 3])
        XCTAssertEqual(records.map(\.type), [.heartRate, .steps, .battery])
    }

    // MARK: - Error paths

    func testEmptyBatchThrows() {
        XCTAssertThrowsError(try HealthTLVDecoder.decodeBatch(Data()))
    }

    func testZeroCountThrows() {
        XCTAssertThrowsError(try HealthTLVDecoder.decodeBatch(Data([0])))
    }

    func testTruncatedPayloadThrows() {
        // Declares a 2-record batch but supplies 1 record
        var w = TestWriter()
        w.u8(2)
        record(&w, type: 1, id: 1) { p in p.u16(60); p.u64(10) }
        XCTAssertThrowsError(try HealthTLVDecoder.decodeBatch(w.data))
    }

    func testPayloadLengthLargerThanActualThrows() {
        // Declares payload len 50 for a 10-byte payload
        var w = TestWriter()
        w.u8(1)
        var pw = TestWriter()
        pw.u64(1)
        pw.u16(60)
        pw.u64(10)
        w.u8(1)
        w.u16(50)
        w.data.append(pw.data)
        XCTAssertThrowsError(try HealthTLVDecoder.decodeBatch(w.data))
    }

    func testUnknownRecordTypeThrows() {
        var w = TestWriter()
        w.u8(1)
        var pw = TestWriter()
        pw.u64(1)
        w.u8(99)
        w.u16(UInt16(pw.data.count))
        w.data.append(pw.data)
        XCTAssertThrowsError(try HealthTLVDecoder.decodeBatch(w.data)) { error in
            XCTAssertEqual(error as? HealthTLVError, .unknownRecordType(99))
        }
    }

    // MARK: - Control codec roundtrips

    func testControlHelloRoundtrip() throws {
        let encoded = HealthControlCodec.encode(.hello(installNonce: 0xDEADBEEF, typeBitmap: 0b0000_1111, cursorRecordId: 4242))
        let decoded = try HealthControlCodec.decode(encoded)
        guard case .hello(let nonce, let bitmap, let cursor) = decoded else { return XCTFail() }
        XCTAssertEqual(nonce, 0xDEADBEEF)
        XCTAssertEqual(bitmap, 0b0000_1111)
        XCTAssertEqual(cursor, 4242)
    }

    func testControlAckRoundtrip() throws {
        let encoded = HealthControlCodec.encode(.ack(recordIds: [10, 11, 12, 999]))
        let decoded = try HealthControlCodec.decode(encoded)
        guard case .ack(let ids) = decoded else { return XCTFail() }
        XCTAssertEqual(ids, [10, 11, 12, 999])
    }

    func testControlTimeSyncRoundtrip() throws {
        let encoded = HealthControlCodec.encode(.timeSync(epochMs: 1_728_000_000_123))
        let decoded = try HealthControlCodec.decode(encoded)
        guard case .timeSync(let epochMs) = decoded else { return XCTFail() }
        XCTAssertEqual(epochMs, 1_728_000_000_123)
    }

    func testControlPingPongRoundtrip() throws {
        let ping = try HealthControlCodec.decode(HealthControlCodec.encode(.ping))
        guard case .ping = ping else { return XCTFail("expected ping") }
        let pong = try HealthControlCodec.decode(HealthControlCodec.encode(.pong))
        guard case .pong = pong else { return XCTFail("expected pong") }
    }

    func testControlBadMessageThrows() {
        XCTAssertThrowsError(try HealthControlCodec.decode(Data([0xFF])))
    }

    // MARK: - Remote trigger control messages

    func testTriggerListGoldenBytes() throws {
        let triggers = [
            RemoteTrigger(index: 0, kind: 0, payload: "com.spotify.music"),
            RemoteTrigger(index: 1, kind: 1, payload: "babydaybook://log/feeding")
        ]
        let encoded = HealthControlCodec.encode(.triggerList(triggers: triggers))

        var expected = TestWriter()
        expected.u8(0x06)
        expected.u8(2)
        expected.u8(0); expected.u8(0)
        let pkg = Array("com.spotify.music".utf8)
        expected.u16(UInt16(pkg.count)); expected.data.append(contentsOf: pkg)
        expected.u8(1); expected.u8(1)
        let uri = Array("babydaybook://log/feeding".utf8)
        expected.u16(UInt16(uri.count)); expected.data.append(contentsOf: uri)
        XCTAssertEqual(encoded, expected.data)

        guard case .triggerList(let decoded) = try HealthControlCodec.decode(encoded) else {
            return XCTFail("expected triggerList")
        }
        XCTAssertEqual(decoded, triggers)
    }

    func testTriggerFireAndResultGoldenBytes() throws {
        let fire = HealthControlCodec.encode(.triggerFire(index: 1))
        XCTAssertEqual(fire, Data([0x07, 1]))
        if case .triggerFire(let index) = try HealthControlCodec.decode(fire) {
            XCTAssertEqual(index, 1)
        } else {
            XCTFail("expected triggerFire")
        }

        let result = HealthControlCodec.encode(.triggerResult(index: 1, result: 3))
        XCTAssertEqual(result, Data([0x08, 1, 3]))
        if case .triggerResult(let index, let code) = try HealthControlCodec.decode(result) {
            XCTAssertEqual(index, 1)
            XCTAssertEqual(code, 3)
        } else {
            XCTFail("expected triggerResult")
        }
    }

    func testRejectsTruncatedTriggerList() {
        let encoded = HealthControlCodec.encode(
            .triggerList(triggers: [RemoteTrigger(index: 0, kind: 0, payload: "com.x")])
        )
        let truncated = encoded.prefix(encoded.count - 2)
        XCTAssertThrowsError(try HealthControlCodec.decode(truncated))
    }
}

/// Watermark persistence: load/advance/reload, monotonic guard, nonce isolation.
final class AckedRecordsStoreTests: XCTestCase {

    private func tempDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("acked-store-tests-\(UUID().uuidString)", isDirectory: true)
        addTeardownBlock {
            try? FileManager.default.removeItem(at: url)
        }
        return url
    }

    func testWatermarkStartsEmpty() {
        let store = AckedRecordsStore(directory: tempDirectory())
        XCTAssertNil(store.watermark(for: 1234))
    }

    func testAdvanceAndPersistAcrossInstances() {
        let dir = tempDirectory()
        let store = AckedRecordsStore(directory: dir)
        store.advanceWatermark(for: 1234, to: 500)
        XCTAssertEqual(store.watermark(for: 1234), 500)

        let reloaded = AckedRecordsStore(directory: dir)
        XCTAssertEqual(reloaded.watermark(for: 1234), 500)
    }

    func testMonotonicGuard() {
        let store = AckedRecordsStore(directory: tempDirectory())
        store.advanceWatermark(for: 7, to: 100)
        store.advanceWatermark(for: 7, to: 40)  // must not regress
        XCTAssertEqual(store.watermark(for: 7), 100)
    }

    func testNonceIsolation() {
        let dir = tempDirectory()
        let store = AckedRecordsStore(directory: dir)
        store.advanceWatermark(for: 1, to: 900)
        // A watch reinstall (fresh nonce) starts from scratch
        XCTAssertNil(store.watermark(for: 2))
        store.advanceWatermark(for: 2, to: 5)
        XCTAssertEqual(store.watermark(for: 1), 900)
        XCTAssertEqual(store.watermark(for: 2), 5)

        let reloaded = AckedRecordsStore(directory: dir)
        XCTAssertEqual(reloaded.watermark(for: 1), 900)
        XCTAssertEqual(reloaded.watermark(for: 2), 5)
    }
}
