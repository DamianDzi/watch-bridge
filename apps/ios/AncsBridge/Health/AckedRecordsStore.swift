import Foundation
import os.log

/// Persisted dedupe state for the health bridge.
///
/// Record IDs are Room autoincrement values on the watch — monotonic per app
/// install. The watch's HELLO carries a per-install nonce, so we track one
/// high-water mark per nonce: a watch reinstall (IDs restart at 1) gets a
/// fresh watermark instead of being swallowed by the old one.
///
/// Invariants relied on by HealthSyncManager:
/// - record IDs are monotonic within an install
/// - iOS acks only a contiguous in-order prefix of a batch
/// Both hold, a high-water mark is therefore a complete seen-set.
final class AckedRecordsStore {

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "AckedRecordsStore")
    private let fileURL: URL
    private let queue = DispatchQueue(label: "com.wearos.ancsbridge.acked-records")
    private var watermarks: [UInt32: Int64]

    init(directory: URL? = nil) {
        let dir = directory ?? AckedRecordsStore.defaultDirectory()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("health-ack-watermarks.json")
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode([String: Int64].self, from: data) {
            watermarks = decoded.reduce(into: [:]) { result, pair in
                if let nonce = UInt32(pair.key) { result[nonce] = pair.value }
            }
        } else {
            watermarks = [:]
        }
    }

    static func defaultDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("WatchBridge", isDirectory: true)
    }

    /// Highest record ID already written to HealthKit for this install nonce
    /// (nil = never synced from this install).
    func watermark(for nonce: UInt32) -> Int64? {
        queue.sync { watermarks[nonce] }
    }

    /// Persists synchronously BEFORE the caller sends the ACK over BLE —
    /// never ack unsaved data. If we crash between persist and ack, the watch
    /// resends records we already have; the watermark skips them.
    func advanceWatermark(for nonce: UInt32, to recordId: Int64) {
        queue.sync {
            guard recordId > (watermarks[nonce] ?? Int64.min) else { return }
            watermarks[nonce] = recordId
            if let data = try? JSONEncoder().encode(watermarks.reduce(into: [String: Int64]()) {
                $0[String($1.key)] = $1.value
            }) {
                do {
                    try data.write(to: fileURL, options: .atomic)
                } catch {
                    logger.error("Failed to persist ack watermarks: \(error.localizedDescription)")
                }
            }
        }
    }
}
