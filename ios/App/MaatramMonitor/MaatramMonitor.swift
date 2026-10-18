import DeviceActivity
import ManagedSettings
import Foundation

/// Runs in its own process. iOS calls intervalDidEnd when the lock time is reached,
/// even if Maatram is closed, and this lifts the shield.
class MaatramMonitor: DeviceActivityMonitor {
    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        ManagedSettingsStore(named: .init("maatramLock")).clearAllSettings()
        UserDefaults(suiteName: "group.com.maatram.app")?.set(0, forKey: "lockUntil")
    }
}
