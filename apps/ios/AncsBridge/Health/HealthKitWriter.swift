import Foundation
import HealthKit
import CoreLocation
import os.log

/// Maps decoded health records to HealthKit objects and saves them.
/// Every object carries HKMetadataKeyExternalUUID = "watch-bridge-<recordId>"
/// so re-imports are identifiable even though dedupe is enforced by
/// AckedRecordsStore before writes happen.
///
/// The workout flow uses HealthKit's async API (beginCollection/addSamples/
/// endCollection/finishWorkout) and associates the GPS route by passing the
/// saved workout to finishRoute(with:metadata:) — signatures verified against
/// the SDK, see specs/health-ble-protocol.md.
final class HealthKitWriter {

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "HealthKitWriter")
    private let store = HKHealthStore()

    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    // MARK: - Authorization

    func requestAuthorization(_ completion: @escaping (Bool) -> Void) {
        guard isAvailable else {
            logger.error("HealthKit not available on this device")
            completion(false)
            return
        }
        let types: Set<HKSampleType> = [
            HKQuantityType(.heartRate),
            HKQuantityType(.stepCount),
            HKQuantityType(.activeEnergyBurned),
            HKQuantityType(.distanceWalkingRunning),
            HKQuantityType(.distanceCycling),
            HKQuantityType(.distanceSwimming),
            HKCategoryType(.sleepAnalysis),
            HKObjectType.workoutType(),
        ]
        store.requestAuthorization(toShare: types, read: nil) { granted, error in
            if let error = error {
                self.logger.error("HealthKit authorization error: \(error.localizedDescription)")
            }
            completion(granted && error == nil)
        }
    }

    // MARK: - Simple samples

    func writeHeartRate(bpm: UInt16, startMs: Int64, recordId: Int64, completion: @escaping (Bool) -> Void) {
        let date = Date(timeIntervalSince1970: Double(startMs) / 1000.0)
        let sample = HKQuantitySample(
            type: HKQuantityType(.heartRate),
            quantity: HKQuantity(unit: .count().unitDivided(by: .minute()), doubleValue: Double(bpm)),
            start: date,
            end: date,
            device: HealthKitWriter.watchDevice,
            metadata: HealthKitWriter.metadata(for: recordId)
        )
        save(sample, completion: completion)
    }

    func writeSteps(count: UInt32, startMs: Int64, endMs: Int64, recordId: Int64, completion: @escaping (Bool) -> Void) {
        let sample = HKQuantitySample(
            type: HKQuantityType(.stepCount),
            quantity: HKQuantity(unit: .count(), doubleValue: Double(count)),
            start: Date(timeIntervalSince1970: Double(startMs) / 1000.0),
            end: Date(timeIntervalSince1970: Double(endMs) / 1000.0),
            device: HealthKitWriter.watchDevice,
            metadata: HealthKitWriter.metadata(for: recordId)
        )
        save(sample, completion: completion)
    }

    func writeSleep(stage: UInt8, startMs: Int64, endMs: Int64, recordId: Int64, completion: @escaping (Bool) -> Void) {
        guard let value = HealthKitWriter.sleepValue(for: stage) else {
            logger.warning("Unknown sleep stage \(stage) — skipping record \(recordId)")
            completion(false)
            return
        }
        let sample = HKCategorySample(
            type: HKCategoryType(.sleepAnalysis),
            value: Int(value.rawValue),
            start: Date(timeIntervalSince1970: Double(startMs) / 1000.0),
            end: Date(timeIntervalSince1970: Double(endMs) / 1000.0),
            device: HealthKitWriter.watchDevice,
            metadata: HealthKitWriter.metadata(for: recordId)
        )
        save(sample, completion: completion)
    }

    /// Fallback when the watch only produced per-stage durations for a night
    /// (no per-interval stage records): slices the window sequentially,
    /// one category sample per stage, in the order the stages were reported.
    /// Approximate by construction — stage interval boundaries are unknown.
    func writeSleepSummary(startMs: Int64, endMs: Int64, stages: [SleepStageDuration], recordId: Int64,
                           completion: @escaping (Bool) -> Void) {
        let window = max(0, endMs - startMs)
        let totalMinutes = stages.reduce(0) { $0 + Int($1.minutes) }
        guard totalMinutes > 0 else {
            completion(false)
            return
        }
        var samples: [HKCategorySample] = []
        var cursor = startMs
        for stage in stages {
            let slice = window * Int64(stage.minutes) / Int64(totalMinutes)
            let end = min(endMs, cursor + slice)
            guard let value = HealthKitWriter.sleepValue(for: stage.stage) else { continue }
            samples.append(HKCategorySample(
                type: HKCategoryType(.sleepAnalysis),
                value: Int(value.rawValue),
                start: Date(timeIntervalSince1970: Double(cursor) / 1000.0),
                end: Date(timeIntervalSince1970: Double(end) / 1000.0),
                device: HealthKitWriter.watchDevice,
                metadata: HealthKitWriter.metadata(for: recordId)
            ))
            cursor = end
            if cursor >= endMs { break }
        }
        guard !samples.isEmpty else {
            completion(false)
            return
        }
        store.save(samples) { success, error in
            if let error = error {
                self.logger.error("Sleep summary save failed: \(error.localizedDescription)")
            }
            completion(success)
        }
    }

    // MARK: - Workouts

    func writeWorkout(
        header: WorkoutHeader,
        hrSamples: [(timestampMs: Int64, bpm: UInt16)],
        gpsPoints: [GpsPoint],
        recordId: Int64,
        completion: @escaping (Bool) -> Void
    ) {
        Task {
            let ok = await self.writeWorkoutAsync(
                store: store,
                header: header,
                hrSamples: hrSamples,
                gpsPoints: gpsPoints,
                recordId: recordId
            )
            completion(ok)
        }
    }

    private func writeWorkoutAsync(
        store: HKHealthStore,
        header: WorkoutHeader,
        hrSamples: [(timestampMs: Int64, bpm: UInt16)],
        gpsPoints: [GpsPoint],
        recordId: Int64
    ) async -> Bool {
        let startDate = Date(timeIntervalSince1970: Double(header.startMs) / 1000.0)
        let endDate = Date(timeIntervalSince1970: Double(header.endMs) / 1000.0)
        let metadata = HealthKitWriter.metadata(for: recordId)

        let config = HKWorkoutConfiguration()
        config.activityType = HealthKitWriter.activityType(for: header.activityType)
        config.locationType = gpsPoints.isEmpty ? .indoor : .outdoor

        let builder = HKWorkoutBuilder(healthStore: store, configuration: config, device: HealthKitWriter.watchDevice)

        let samples = HealthKitWriter.workoutSamples(header: header, hrSamples: hrSamples, startDate: startDate, endDate: endDate)
        let locations = HealthKitWriter.workoutLocations(gpsPoints)

        do {
            try await builder.beginCollection(at: startDate)
            // Metadata failure is non-fatal; the workout itself matters.
            try? await builder.addMetadata(metadata)
            try await builder.addSamples(samples)
            try await builder.endCollection(at: endDate)
            guard let workout = try await builder.finishWorkout() else {
                logger.error("Workout write failed: finishWorkout returned no workout")
                return false
            }

            if !locations.isEmpty {
                let routeBuilder = HKWorkoutRouteBuilder(healthStore: store, device: HealthKitWriter.watchDevice)
                try await routeBuilder.insertRouteData(locations)
                // Route failure is non-fatal — the workout is already saved.
                _ = try? await routeBuilder.finishRoute(with: workout, metadata: metadata)
            }
            return true
        } catch {
            logger.error("Workout write failed: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    private static func workoutSamples(
        header: WorkoutHeader,
        hrSamples: [(timestampMs: Int64, bpm: UInt16)],
        startDate: Date,
        endDate: Date
    ) -> [HKSample] {
        var samples: [HKSample] = hrSamples.map { sample in
            let date = Date(timeIntervalSince1970: Double(sample.timestampMs) / 1000.0)
            return HKQuantitySample(
                type: HKQuantityType(.heartRate),
                quantity: HKQuantity(unit: .count().unitDivided(by: .minute()), doubleValue: Double(sample.bpm)),
                start: date,
                end: date,
                device: HealthKitWriter.watchDevice,
                metadata: nil
            )
        }
        if header.caloriesTenths > 0 {
            samples.append(HKQuantitySample(
                type: HKQuantityType(.activeEnergyBurned),
                quantity: HKQuantity(unit: .kilocalorie(), doubleValue: Double(header.caloriesTenths) / 10.0),
                start: startDate,
                end: endDate,
                device: HealthKitWriter.watchDevice,
                metadata: nil
            ))
        }
        if header.distanceMeters > 0, let distanceType = HealthKitWriter.distanceType(for: header.activityType) {
            samples.append(HKQuantitySample(
                type: distanceType,
                quantity: HKQuantity(unit: .meter(), doubleValue: Double(header.distanceMeters)),
                start: startDate,
                end: endDate,
                device: HealthKitWriter.watchDevice,
                metadata: nil
            ))
        }
        return samples
    }

    private static func workoutLocations(_ gpsPoints: [GpsPoint]) -> [CLLocation] {
        gpsPoints.map { point in
            CLLocation(
                coordinate: CLLocationCoordinate2D(
                    latitude: Double(point.latitudeE7) / 1e7,
                    longitude: Double(point.longitudeE7) / 1e7
                ),
                altitude: CLLocationDistance(point.altitudeMeters),
                horizontalAccuracy: 5.0,
                verticalAccuracy: 5.0,
                timestamp: Date(timeIntervalSince1970: Double(point.timestampMs) / 1000.0)
            )
        }
    }

    // MARK: - Shared helpers

    private func save(_ sample: HKSample, completion: @escaping (Bool) -> Void) {
        store.save(sample) { success, error in
            if let error = error {
                self.logger.error("Sample save failed: \(error.localizedDescription)")
            }
            completion(success)
        }
    }

    /// HKDevice's named-field initializer is unavailable on macOS; the iOS
    /// build gets the full device attribution.
    static let watchDevice: HKDevice? = {
        #if os(iOS)
        return HKDevice(name: "OnePlus Watch 3 (watch-bridge)", firmwareVersion: nil, softwareVersion: nil,
                        hardwareVersion: nil, localIdentifier: nil, udid: nil)
        #else
        return nil
        #endif
    }()

    static func metadata(for recordId: Int64) -> [String: Any] {
        [HKMetadataKeyExternalUUID: "watch-bridge-\(recordId)"]
    }

    static func sleepValue(for stage: UInt8) -> HKCategoryValueSleepAnalysis? {
        switch stage {
        case 0: return .awake
        case 1: return .asleepCore
        case 2: return .asleepDeep
        case 3: return .asleepREM
        case 4: return .inBed
        default: return nil
        }
    }

    static func activityType(for raw: UInt8) -> HKWorkoutActivityType {
        switch raw {
        case 0: return .running
        case 1: return .walking
        case 2: return .cycling
        case 3: return .swimming
        case 4: return .traditionalStrengthTraining
        case 5: return .yoga
        case 6: return .hiking
        case 7: return .elliptical
        case 8: return .rowing
        case 9: return .highIntensityIntervalTraining
        case 10: return .dance
        case 11: return .boxing
        case 12: return .pilates
        case 13: return .gymnastics
        case 14: return .golf
        case 15: return .tennis
        case 16: return .basketball
        case 17: return .soccer
        case 18: return .rugby
        case 19: return .cricket
        case 20: return .downhillSkiing
        case 21: return .snowboarding
        case 22: return .skatingSports
        case 23: return .climbing
        default: return .other
        }
    }

    private static func distanceType(for raw: UInt8) -> HKQuantityType? {
        switch raw {
        case 0, 1, 6: return HKQuantityType(.distanceWalkingRunning)
        case 2: return HKQuantityType(.distanceCycling)
        case 3: return HKQuantityType(.distanceSwimming)
        default: return nil
        }
    }
}
