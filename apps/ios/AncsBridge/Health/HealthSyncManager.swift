import Foundation
import Combine
import os.log

/// Channel the sync manager uses to push control notifications (ACK,
/// TIME_SYNC, PONG) back to the watch. Implemented by PeripheralManager.
protocol HealthControlChannel: AnyObject {
    @discardableResult func sendHealthControl(_ data: Data) -> Bool
}

/// Orchestrates the health bridge on the iOS side:
/// health-data writes (B6) → decode → dedupe (watermark) → HealthKit writes
/// → in-order ACK via health-control (B5).
///
/// Acking rules (keep these invariants — the watermark dedupe depends on them):
/// - Only records successfully written to HealthKit are acked.
/// - Acks are a contiguous in-order prefix of each batch; the first failure
///   stops processing (watch resends from that record).
/// - The watermark is persisted BEFORE the ACK goes out.
/// - Workout stream records are acked only when the complete workout
///   (header + declared HR/GPS counts) is written.
final class HealthSyncManager: ObservableObject {

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "HealthSyncManager")
    private let writer: HealthKitWriter
    private let ackedStore: AckedRecordsStore
    private let processingQueue = DispatchQueue(label: "com.wearos.ancsbridge.health-sync")

    weak var channel: HealthControlChannel?

    /// Supplies the current remote-trigger list (owned by SettingsStore);
    /// pushed to the watch after every HELLO and on edits.
    var triggersProvider: (() -> [RemoteTrigger])?

    @Published private(set) var isHealthKitAuthorized = false
    @Published private(set) var lastSync: Date?
    @Published private(set) var recordsWritten = 0
    @Published private(set) var perTypeCounts: [String: Int] = [:]
    @Published private(set) var pendingWorkout = false
    @Published private(set) var lastBatteryPercent: UInt8?
    @Published private(set) var lastError: String?
    @Published private(set) var lastTriggerOutcome: TriggerOutcome?

    struct TriggerOutcome: Equatable {
        let index: UInt8
        let result: UInt8
        let at: Date
    }

    private var installNonce: UInt32?

    /// Workout under construction: header arrived, stream chunks accumulating.
    private final class PendingWorkout {
        let header: WorkoutHeader
        var hrSamples: [(timestampMs: Int64, bpm: UInt16)] = []
        var gpsPoints: [GpsPoint] = []
        var recordIds: [Int64]      // header + all chunk ids, acked together on flush
        var seenIds: Set<Int64>     // chunks may be re-sent before the ack round-trips

        init(header: WorkoutHeader, headerRecordId: Int64) {
            self.header = header
            self.recordIds = [headerRecordId]
            self.seenIds = [headerRecordId]
        }

        var isComplete: Bool {
            hrSamples.count >= Int(header.hrSampleCount) && gpsPoints.count >= Int(header.gpsPointCount)
        }
    }

    private var pending: PendingWorkout?

    init(writer: HealthKitWriter = HealthKitWriter(), ackedStore: AckedRecordsStore = AckedRecordsStore()) {
        self.writer = writer
        self.ackedStore = ackedStore
        writer.requestAuthorization { [weak self] granted in
            DispatchQueue.main.async {
                self?.isHealthKitAuthorized = granted
                if !granted {
                    self?.lastError = "HealthKit not authorized — health sync inactive"
                }
            }
        }
    }

    // MARK: - Control characteristic (B5) writes from the watch

    func handleControlWrite(_ data: Data) {
        do {
            switch try HealthControlCodec.decode(data) {
            case .hello(let nonce, _, _):
                installNonce = nonce
                logger.info("Health HELLO: install nonce \(nonce)")
                _ = channel?.sendHealthControl(HealthControlCodec.encode(.timeSync(epochMs: currentEpochMs())))
                pushTriggerList()
            case .ping:
                logger.debug("Health PING")
                _ = channel?.sendHealthControl(HealthControlCodec.encode(.pong))
            case .triggerResult(let index, let result):
                logger.info("Trigger result: index \(index) result \(result)")
                DispatchQueue.main.async {
                    self.lastTriggerOutcome = TriggerOutcome(index: index, result: result, at: Date())
                }
            case .ack, .timeSync, .pong:
                logger.warning("Unexpected control message from watch (iOS-only command)")
            case .triggerList, .triggerFire:
                logger.warning("Unexpected control message from watch (watch-only command)")
            }
        } catch {
            reportError("Bad control message: \(error)")
        }
    }

    // MARK: - Remote triggers

    /// Sends the current trigger list to the watch (full replacement).
    func pushTriggerList() {
        let triggers = triggersProvider?() ?? []
        guard !triggers.isEmpty else { return }
        let encoded = HealthControlCodec.encode(.triggerList(triggers: triggers))
        guard encoded.count <= 500 else {
            reportError("Trigger list too large (\(encoded.count) B > 500) — remove some triggers")
            return
        }
        _ = channel?.sendHealthControl(encoded)
    }

    /// Fires a stored trigger on the watch; feedback arrives via lastTriggerOutcome.
    func fireTrigger(_ index: UInt8) {
        _ = channel?.sendHealthControl(HealthControlCodec.encode(.triggerFire(index: index)))
    }

    // MARK: - Data characteristic (B6) writes from the watch

    func handleDataWrite(_ data: Data) {
        let records: [HealthRecord]
        do {
            records = try HealthTLVDecoder.decodeBatch(data)
        } catch {
            reportError("Bad health batch: \(error)")
            return
        }
        processingQueue.async { [weak self] in
            self?.process(records: records, index: 0, ackedIds: [])
        }
    }

    // MARK: - Record pipeline (serial, completion-chained)

    private func process(records: [HealthRecord], index: Int, ackedIds: [Int64]) {
        guard index < records.count else {
            finishBatch(ackedIds: ackedIds)
            return
        }
        let record = records[index]

        // Already written in a previous session/batch → re-ack so the watch
        // can delete its row (e.g. crash between our persist and its delete).
        if let nonce = installNonce,
           let watermark = ackedStore.watermark(for: nonce),
           record.recordId <= watermark {
            process(records: records, index: index + 1, ackedIds: ackedIds + [record.recordId])
            return
        }

        switch record.payload {
        case .battery(let percent, _):
            DispatchQueue.main.async { self.lastBatteryPercent = percent }
            process(records: records, index: index + 1, ackedIds: ackedIds + [record.recordId])

        case .heartRate(let bpm, let startMs):
            writer.writeHeartRate(bpm: bpm, startMs: startMs, recordId: record.recordId) { [weak self] ok in
                self?.continueAfterSimpleWrite(ok, record: record, records: records,
                                               index: index, ackedIds: ackedIds)
            }

        case .steps(let count, let startMs, let endMs):
            writer.writeSteps(count: count, startMs: startMs, endMs: endMs, recordId: record.recordId) { [weak self] ok in
                self?.continueAfterSimpleWrite(ok, record: record, records: records,
                                               index: index, ackedIds: ackedIds)
            }

        case .sleep(let stage, let startMs, let endMs):
            writer.writeSleep(stage: stage, startMs: startMs, endMs: endMs, recordId: record.recordId) { [weak self] ok in
                self?.continueAfterSimpleWrite(ok, record: record, records: records,
                                               index: index, ackedIds: ackedIds)
            }

        case .sleepSummary(let startMs, let endMs, let stages):
            writer.writeSleepSummary(startMs: startMs, endMs: endMs, stages: stages, recordId: record.recordId) { [weak self] ok in
                self?.continueAfterSimpleWrite(ok, record: record, records: records,
                                               index: index, ackedIds: ackedIds)
            }

        case .workoutHeader(let header):
            // A new header means the previous workout (if any) never completed
            // its declared counts — flush what we have so its data isn't lost.
            flushPendingWorkout { [weak self] flushedIds in
                guard let self = self else { return }
                self.pending = PendingWorkout(header: header, headerRecordId: record.recordId)
                DispatchQueue.main.async { self.pendingWorkout = true }
                self.process(records: records, index: index + 1, ackedIds: ackedIds + flushedIds)
            }

        case .workoutHrStream(let samples):
            guard let pending = pending, !pending.seenIds.contains(record.recordId) else {
                // Orphan stream chunk (no header / duplicate) — don't ack; watch resends.
                reportError("HR stream without matching workout header (record \(record.recordId))")
                process(records: records, index: index + 1, ackedIds: ackedIds)
                return
            }
            pending.hrSamples.append(contentsOf: samples)
            pending.recordIds.append(record.recordId)
            pending.seenIds.insert(record.recordId)
            if pending.isComplete {
                flushPendingWorkout { [weak self] flushedIds in
                    guard let self = self else { return }
                    self.process(records: records, index: index + 1, ackedIds: ackedIds + flushedIds)
                }
            } else {
                process(records: records, index: index + 1, ackedIds: ackedIds)
            }

        case .workoutGps(let points):
            guard let pending = pending, !pending.seenIds.contains(record.recordId) else {
                reportError("GPS chunk without matching workout header (record \(record.recordId))")
                process(records: records, index: index + 1, ackedIds: ackedIds)
                return
            }
            pending.gpsPoints.append(contentsOf: points)
            pending.recordIds.append(record.recordId)
            pending.seenIds.insert(record.recordId)
            if pending.isComplete {
                flushPendingWorkout { [weak self] flushedIds in
                    guard let self = self else { return }
                    self.process(records: records, index: index + 1, ackedIds: ackedIds + flushedIds)
                }
            } else {
                process(records: records, index: index + 1, ackedIds: ackedIds)
            }
        }
    }

    private func continueAfterSimpleWrite(_ ok: Bool, record: HealthRecord,
                                          records: [HealthRecord], index: Int, ackedIds: [Int64]) {
        if ok {
            DispatchQueue.main.async {
                self.recordsWritten += 1
                let key = "\(record.type)"
                self.perTypeCounts[key, default: 0] += 1
            }
            process(records: records, index: index + 1, ackedIds: ackedIds + [record.recordId])
        } else {
            // In-order invariant: stop at the first failure, ack the prefix.
            reportError("HealthKit write failed at record \(record.recordId) — batch stops here")
            finishBatch(ackedIds: ackedIds)
        }
    }

    private func flushPendingWorkout(completion: @escaping ([Int64]) -> Void) {
        guard let pending = pending else {
            completion([])
            return
        }
        self.pending = nil
        DispatchQueue.main.async { self.pendingWorkout = false }
        writer.writeWorkout(
            header: pending.header,
            hrSamples: pending.hrSamples,
            gpsPoints: pending.gpsPoints,
            recordId: pending.recordIds.first ?? 0
        ) { [weak self] ok in
            guard let self = self else {
                completion([])
                return
            }
            if ok {
                DispatchQueue.main.async {
                    self.recordsWritten += 1
                    self.perTypeCounts["workout", default: 0] += 1
                }
                completion(pending.recordIds)
            } else {
                // Drop the partial; the un-acked ids make the watch resend
                // the whole workout (header + chunks) on the next round.
                self.reportError("Workout flush failed — will be resent by watch")
                completion([])
            }
        }
    }

    private func finishBatch(ackedIds: [Int64]) {
        guard !ackedIds.isEmpty else {
            DispatchQueue.main.async { self.lastSync = Date() }
            return
        }
        if let nonce = installNonce {
            // Persist BEFORE the ACK leaves — never ack unsaved data.
            ackedStore.advanceWatermark(for: nonce, to: ackedIds.max() ?? Int64.min)
        }
        let ack = HealthControlCodec.encode(.ack(recordIds: ackedIds))
        channel?.sendHealthControl(ack)
        DispatchQueue.main.async { self.lastSync = Date() }
    }

    // MARK: - Helpers

    private func currentEpochMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func reportError(_ message: String) {
        logger.error("\(message, privacy: .public)")
        DispatchQueue.main.async { self.lastError = message }
    }
}
