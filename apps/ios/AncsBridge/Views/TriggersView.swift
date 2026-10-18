import SwiftUI

/// Remote triggers: launch watch apps or open deep links on the watch from
/// the phone (protocol §Remote triggers). The full list is pushed to the
/// watch on connect and after every edit; rows fire by index — payloads
/// never travel with a fire command.
struct TriggersView: View {
    @EnvironmentObject var viewModel: BLEViewModel
    @State private var showingAddSheet = false
    @State private var lastOutcomeText: String?

    var body: some View {
        List {
            Section {
                if viewModel.peripheralManager.settingsStore.remoteTriggers.isEmpty {
                    Text("No triggers yet. Add one to launch a watch app or open a link from this phone.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                ForEach(viewModel.peripheralManager.settingsStore.remoteTriggers, id: \.index) { trigger in
                    Button {
                        viewModel.peripheralManager.healthSyncManager.fireTrigger(trigger.index)
                    } label: {
                        TriggerRowLabel(trigger: trigger)
                    }
                }
                .onDelete(perform: deleteTriggers)
            } header: {
                Text("Triggers")
            } footer: {
                Text("Tap a trigger to run it on the watch. Example: package com.spotify.music, or a Baby Daybook deep link.")
            }

            if let lastOutcomeText {
                Section {
                    Text(lastOutcomeText)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
        }
        .navigationTitle("Remote Triggers")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showingAddSheet = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showingAddSheet) {
            AddTriggerView { kind, payload in
                addTrigger(kind: kind, payload: payload)
            }
        }
        .onReceive(viewModel.peripheralManager.healthSyncManager.$lastTriggerOutcome) { outcome in
            if let outcome {
                lastOutcomeText = Self.outcomeText(outcome)
            }
        }
    }

    private func addTrigger(kind: UInt8, payload: String) {
        let store = viewModel.peripheralManager.settingsStore
        let nextIndex = store.remoteTriggers.map(\.index).max().map { $0 + 1 } ?? 0
        store.remoteTriggers.append(RemoteTrigger(index: nextIndex, kind: kind, payload: payload))
        viewModel.peripheralManager.healthSyncManager.pushTriggerList()
    }

    private func deleteTriggers(at offsets: IndexSet) {
        let store = viewModel.peripheralManager.settingsStore
        store.remoteTriggers.remove(atOffsets: offsets)
        viewModel.peripheralManager.healthSyncManager.pushTriggerList()
    }

    static func outcomeText(_ outcome: HealthSyncManager.TriggerOutcome) -> String {
        let name = outcome.result == RemoteTriggerResult.ok.rawValue ? "✓" : "⚠️"
        let detail: String
        switch outcome.result {
        case RemoteTriggerResult.ok.rawValue: detail = "Launched on the watch."
        case RemoteTriggerResult.noSuchTrigger.rawValue: detail = "Watch has no such trigger — reconnect or re-edit the list."
        case RemoteTriggerResult.launchFailed.rawValue: detail = "Watch could not launch it (app or link not found)."
        case RemoteTriggerResult.blockedBySystem.rawValue: detail = "System blocked direct launch — a one-tap notification was posted on the watch."
        default: detail = "Unknown result \(outcome.result)."
        }
        return "\(name) Trigger \(outcome.index): \(detail)"
    }
}

private struct TriggerRowLabel: View {
    let trigger: RemoteTrigger

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(trigger.payload)
                    .font(.subheadline)
                    .foregroundColor(.primary)
                    .multilineTextAlignment(.leading)
                Text(trigger.kind == 0 ? "Launch app" : "Open link")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        } icon: {
            Image(systemName: trigger.kind == 0 ? "app.badge" : "link")
                .foregroundColor(.orange)
        }
    }
}

private struct AddTriggerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var kind: UInt8 = 0
    @State private var payload = ""

    var onAdd: (UInt8, String) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Picker("Type", selection: $kind) {
                    Text("Launch watch app (package)").tag(UInt8(0))
                    Text("Open deep link (URI)").tag(UInt8(1))
                }
                TextField(
                    kind == 0 ? "com.spotify.music" : "babydaybook://log/feeding",
                    text: $payload
                )
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                Section {
                    Text("Find the package name in the watch's Play Store page URL (after ?id=).")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("New Trigger")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onAdd(kind, payload.trimmingCharacters(in: .whitespaces))
                        dismiss()
                    }
                    .disabled(payload.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
