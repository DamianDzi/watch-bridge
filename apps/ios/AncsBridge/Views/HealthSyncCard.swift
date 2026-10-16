import SwiftUI

/// Compact health-bridge status card: last sync, records written, per-type
/// counts, watch battery, and the last error (if any).
struct HealthSyncCard: View {
    @ObservedObject var health: HealthSyncManager

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: health.isHealthKitAuthorized ? "heart.circle.fill" : "heart.slash.circle")
                    .font(.title3)
                    .foregroundColor(health.isHealthKitAuthorized ? .pink : .secondary)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Watch → Apple Health")
                        .font(.body)
                        .fontWeight(.medium)
                    Text(health.isHealthKitAuthorized ? "Authorized" : "HealthKit access not granted")
                        .font(.caption)
                        .foregroundColor(health.isHealthKitAuthorized ? .secondary : .orange)
                }

                Spacer()

                if health.pendingWorkout {
                    Text("Workout in progress")
                        .font(.caption2)
                        .foregroundColor(.blue)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.blue.opacity(0.1))
                        .cornerRadius(8)
                }
            }

            HStack(spacing: 16) {
                statTile(value: health.lastSync.map { Self.relativeFormatter.localizedString(for: $0, relativeTo: Date()) } ?? "—",
                         label: "Last sync")
                statTile(value: "\(health.recordsWritten)", label: "Records written")
                statTile(value: health.lastBatteryPercent.map { "\($0)%" } ?? "—", label: "Watch battery")
            }

            if !health.perTypeCounts.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Written by type")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    ForEach(health.perTypeCounts.sorted(by: { $0.value > $1.value }), id: \.key) { entry in
                        HStack {
                            Text(Self.typeLabel(entry.key))
                                .font(.caption)
                            Spacer()
                            Text("\(entry.value)")
                                .font(.caption.monospacedDigit())
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding(.top, 2)
            }

            if let error = health.lastError {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.red)
                    .lineLimit(2)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
    }

    private func statTile(value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.subheadline.monospacedDigit())
                .fontWeight(.semibold)
            Text(label)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private static let relativeFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter
    }()

    private static func typeLabel(_ key: String) -> String {
        switch key {
        case "heartRate": return "Heart rate"
        case "steps": return "Steps"
        case "sleep": return "Sleep stages"
        case "sleepSummary": return "Sleep summary"
        case "workout": return "Workouts"
        case "workoutHeader", "workoutHrStream", "workoutGps": return "Workout data"
        case "battery": return "Battery"
        default: return key
        }
    }
}
