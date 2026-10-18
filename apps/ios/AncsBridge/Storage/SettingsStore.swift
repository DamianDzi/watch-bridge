import Foundation

/// UserDefaults-backed settings storage.
class SettingsStore: ObservableObject {
    private let defaults = UserDefaults.standard

    private enum Keys {
        static let autoAdvertise = "autoAdvertise"
        static let pairedDevices = "pairedDevices"
        static let liveCallStatus = "liveCallStatus"
        static let remoteTriggers = "remoteTriggers"
        static let watchApps = "watchApps"
        static let watchAppsFetchedAt = "watchAppsFetchedAt"
    }

    @Published var autoAdvertise: Bool {
        didSet { defaults.set(autoAdvertise, forKey: Keys.autoAdvertise) }
    }

    /// Remote triggers the iPhone can fire on the watch (protocol §Remote
    /// triggers). Callers push the list to the watch after edits.
    @Published var remoteTriggers: [RemoteTrigger] {
        didSet {
            if let data = try? JSONEncoder().encode(remoteTriggers) {
                defaults.set(data, forKey: Keys.remoteTriggers)
            }
        }
    }

    /// Last watch app list fetched over BLE (protocol §Watch app list) —
    /// the picker works offline from this cache. `watchAppsFetchedAt` shows
    /// its age.
    @Published var watchApps: [RemoteAppInfo] {
        didSet {
            if let data = try? JSONEncoder().encode(watchApps) {
                defaults.set(data, forKey: Keys.watchApps)
            }
        }
    }
    @Published var watchAppsFetchedAt: Date? {
        didSet { defaults.set(watchAppsFetchedAt, forKey: Keys.watchAppsFetchedAt) }
    }

    /// When enabled, uses CallKit to monitor phone call state and pushes
    /// real-time updates to the watch over BLE. Off by default — requires
    /// the companion app to be running during calls.
    @Published var liveCallStatus: Bool {
        didSet { defaults.set(liveCallStatus, forKey: Keys.liveCallStatus) }
    }

    init() {
        self.autoAdvertise = defaults.bool(forKey: Keys.autoAdvertise)
        self.liveCallStatus = defaults.bool(forKey: Keys.liveCallStatus)
        if let data = defaults.data(forKey: Keys.remoteTriggers),
           let triggers = try? JSONDecoder().decode([RemoteTrigger].self, from: data) {
            self.remoteTriggers = triggers
        } else {
            self.remoteTriggers = []
        }
        if let data = defaults.data(forKey: Keys.watchApps),
           let apps = try? JSONDecoder().decode([RemoteAppInfo].self, from: data) {
            self.watchApps = apps
        } else {
            self.watchApps = []
        }
        self.watchAppsFetchedAt = defaults.object(forKey: Keys.watchAppsFetchedAt) as? Date
        // Default to false — discoverability only via Add New Watch flow
        if !defaults.contains(key: Keys.autoAdvertise) {
            self.autoAdvertise = false
        }
        // Default to false — opt-in for live call status
        if !defaults.contains(key: Keys.liveCallStatus) {
            self.liveCallStatus = false
        }
    }

    func savePairedDevice(_ device: PairedDevice) {
        var devices = loadPairedDevices()
        if let index = devices.firstIndex(where: { $0.id == device.id }) {
            devices[index] = device
        } else {
            devices.append(device)
        }
        if let data = try? JSONEncoder().encode(devices) {
            defaults.set(data, forKey: Keys.pairedDevices)
        }
    }

    func loadPairedDevices() -> [PairedDevice] {
        guard let data = defaults.data(forKey: Keys.pairedDevices),
              let devices = try? JSONDecoder().decode([PairedDevice].self, from: data) else {
            return []
        }
        return devices
    }

    func removePairedDevice(id: String) {
        var devices = loadPairedDevices()
        devices.removeAll { $0.id == id }
        if let data = try? JSONEncoder().encode(devices) {
            defaults.set(data, forKey: Keys.pairedDevices)
        }
    }
}

private extension UserDefaults {
    func contains(key: String) -> Bool {
        return object(forKey: key) != nil
    }
}
