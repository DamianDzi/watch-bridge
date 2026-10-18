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
            AddTriggerView(
                cachedApps: viewModel.peripheralManager.settingsStore.watchApps,
                cachedAt: viewModel.peripheralManager.settingsStore.watchAppsFetchedAt
                    ?? viewModel.peripheralManager.healthSyncManager.watchAppsFetchedAt,
                isWatchConnected: viewModel.peripheralManager.isWatchSubscribed,
                onRefresh: {
                    viewModel.peripheralManager.healthSyncManager.requestAppList()
                },
                isFetching: viewModel.peripheralManager.healthSyncManager.isFetchingWatchApps
            ) { kind, payload in
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
    @State private var manualEntry = false
    @State private var searchText = ""

    /// Cached watch app list (SettingsStore) — the picker works offline.
    let cachedApps: [RemoteAppInfo]
    let cachedAt: Date?
    let isWatchConnected: Bool
    let onRefresh: () -> Void
    let isFetching: Bool
    let onAdd: (UInt8, String) -> Void

    private var sortedApps: [RemoteAppInfo] {
        cachedApps.sorted { $0.label.lowercased() < $1.label.lowercased() }
    }

    private var filteredApps: [RemoteAppInfo] {
        let query = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return sortedApps }
        return sortedApps.filter {
            $0.label.lowercased().contains(query) || $0.packageName.lowercased().contains(query)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker("Type", selection: $kind) {
                    Text("Launch watch app").tag(UInt8(0))
                    Text("Open deep link (URI)").tag(UInt8(1))
                }

                if kind == 0 {
                    appPickerSection
                } else {
                    TextField(
                        "babydaybook://log/feeding",
                        text: $payload
                    )
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    Section {
                        Text("The watch opens this URI with the app that handles it.")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
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

    private var appPickerSection: some View {
        Section {
            if manualEntry {
                TextField("com.spotify.music", text: $payload)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } else {
                TextField("Search apps", text: $searchText)
                    .autocorrectionDisabled()
                if sortedApps.isEmpty {
                    Text("No watch app list yet. Tap the ↻ button with the watch connected, or enter the package manually.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                } else {
                    ForEach(filteredApps, id: \.packageName) { app in
                        Button {
                            payload = app.packageName
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(app.label)
                                        .font(.subheadline)
                                        .foregroundColor(.primary)
                                    Text(app.packageName)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                                if payload == app.packageName {
                                    Image(systemName: "checkmark")
                                        .foregroundColor(.orange)
                                }
                            }
                        }
                    }
                }
            }

            Toggle("Enter package manually", isOn: $manualEntry)

            HStack {
                if let cachedAt, !manualEntry {
                    Text("Fetched \(Self.relativeTime(cachedAt)) ago")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                Spacer()
                Button {
                    onRefresh()
                } label: {
                    if isFetching {
                        ProgressView()
                    } else {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                }
                .disabled(!isWatchConnected || isFetching)
            }
        } header: {
            Text("Watch apps")
        } footer: {
            if !isWatchConnected {
                Text("Watch not connected — refresh needs the bridge link up.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
        }
    }

    private static func relativeTime(_ date: Date) -> String {
        let seconds = max(0, Int(Date().timeIntervalSince(date)))
        if seconds < 60 { return "\(seconds)s" }
        if seconds < 3600 { return "\(seconds / 60)m" }
        return "\(seconds / 3600)h"
    }
}
