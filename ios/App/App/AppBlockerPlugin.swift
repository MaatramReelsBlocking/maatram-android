import Foundation
import UIKit
import SwiftUI
import Capacitor
import FamilyControls
import ManagedSettings
import DeviceActivity

/// iOS twin of the Android AppBlocker plugin, same JS name so www/app-gate.html keeps working.
/// Hard Lock = ManagedSettings shield on the apps the user picked, ended by the
/// DeviceActivityMonitor extension (so it ends even if Maatram is closed).
/// The Android friction gate has no iOS equivalent: its methods are inert.
@objc(AppBlockerPlugin)
public class AppBlockerPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "AppBlockerPlugin"
    public let jsName = "AppBlocker"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "requestAuthorization", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pickApps", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setLock", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearLock", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setWhatsappAllowed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setFrictionEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getFrictionState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "syncPoints", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "drainEvents", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openUrl", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "consumeAuthToken", returnType: CAPPluginReturnPromise),
    ]

    static let group = "group.com.maatram.app"
    static let storeName = ManagedSettingsStore.Name("maatramLock")
    static let activity = DeviceActivityName("maatramLock")
    private let defaults = UserDefaults(suiteName: AppBlockerPlugin.group) ?? .standard
    private let store = ManagedSettingsStore(named: AppBlockerPlugin.storeName)

    // MARK: selections (stored as JSON in the shared app group)

    private func load(_ key: String) -> FamilyActivitySelection {
        guard let d = defaults.data(forKey: key),
              let s = try? JSONDecoder().decode(FamilyActivitySelection.self, from: d) else { return FamilyActivitySelection() }
        return s
    }
    private func save(_ s: FamilyActivitySelection, _ key: String) {
        defaults.set(try? JSONEncoder().encode(s), forKey: key)
    }
    private func isEmpty(_ s: FamilyActivitySelection) -> Bool {
        s.applicationTokens.isEmpty && s.categoryTokens.isEmpty && s.webDomainTokens.isEmpty
    }
    private var authorized: Bool { AuthorizationCenter.shared.authorizationStatus == .approved }
    private var lockUntil: Date { Date(timeIntervalSince1970: defaults.double(forKey: "lockUntil")) }

    // MARK: shield

    /// Shield the "block" selection, minus the "allow" apps while the allow-list switch is on.
    private func applyShield() {
        let block = load("block")
        let allow = defaults.bool(forKey: "allowOn") ? load("allow").applicationTokens : []
        store.shield.applications = block.applicationTokens.subtracting(allow)
        store.shield.applicationCategories = block.categoryTokens.isEmpty
            ? nil : ShieldSettings.ActivityCategoryPolicy.specific(block.categoryTokens, except: allow)
        store.shield.webDomains = block.webDomainTokens
    }

    private func clearShield() {
        store.clearAllSettings()
        DeviceActivityCenter().stopMonitoring([AppBlockerPlugin.activity])
        defaults.set(0, forKey: "lockUntil")
    }

    /// Safety net: if the lock time has passed and the extension did not run yet, end it now.
    private func expireIfDue() {
        let u = defaults.double(forKey: "lockUntil")
        if u > 0 && Date(timeIntervalSince1970: u) <= Date() { clearShield() }
    }

    // MARK: JS API

    @objc func requestAuthorization(_ call: CAPPluginCall) {
        Task {
            do {
                try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
                call.resolve(["authorized": self.authorized])
            } catch {
                call.reject("Screen Time permission failed: \(error.localizedDescription)")
            }
        }
    }

    /// kind: "block" (apps to lock) or "allow" (apps that stay open when allow-list is on)
    @objc func pickApps(_ call: CAPPluginCall) {
        let kind = call.getString("kind") == "allow" ? "allow" : "block"
        guard authorized else { call.reject("Allow Screen Time access first"); return }
        DispatchQueue.main.async {
            guard let vc = self.bridge?.viewController else { call.reject("No view"); return }
            var host: UIViewController?
            let view = AppPickerView(initial: self.load(kind), title: kind == "allow" ? "Apps to keep open" : "Apps to lock") { sel in
                if let sel = sel {
                    self.save(sel, kind)
                    if self.lockUntil > Date() { self.applyShield() }   // live lock: apply the change now
                }
                host?.dismiss(animated: true)
                let s = self.load(kind)
                call.resolve(["apps": s.applicationTokens.count, "categories": s.categoryTokens.count, "sites": s.webDomainTokens.count])
            }
            host = UIHostingController(rootView: view)
            vc.present(host!, animated: true)
        }
    }

    /// until = epoch ms as a string (same contract as Android). A running lock can only be extended.
    @objc func setLock(_ call: CAPPluginCall) {
        guard authorized else { call.reject("Allow Screen Time access first"); return }
        guard !isEmpty(load("block")) else { call.reject("Pick the apps to lock first"); return }
        guard let raw = call.getString("until") ?? call.getDouble("until").map({ String(Int64($0)) }),
              let ms = Double(raw), ms > 0 else { call.reject("until (epoch ms) required"); return }
        let until = max(Date(timeIntervalSince1970: ms / 1000), lockUntil)
        guard until > Date() else { call.reject("until is in the past"); return }
        if let wa = call.getBool("whatsappAllowed") { defaults.set(wa, forKey: "allowOn") }
        defaults.set(until.timeIntervalSince1970, forKey: "lockUntil")
        applyShield()
        // The monitor extension ends the lock at `until`. iOS refuses schedules under ~15 min:
        // for those the lock still ends via expireIfDue() the next time Maatram is opened.
        let cal = Calendar.current
        let c: Set<Calendar.Component> = [.year, .month, .day, .hour, .minute, .second]
        let schedule = DeviceActivitySchedule(intervalStart: cal.dateComponents(c, from: Date()),
                                              intervalEnd: cal.dateComponents(c, from: until), repeats: false)
        var scheduled = true
        do { try DeviceActivityCenter().startMonitoring(AppBlockerPlugin.activity, during: schedule) }
        catch { scheduled = false }
        call.resolve(["scheduled": scheduled])
    }

    @objc func clearLock(_ call: CAPPluginCall) { clearShield(); call.resolve() }

    @objc func setWhatsappAllowed(_ call: CAPPluginCall) {
        defaults.set(call.getBool("allowed") ?? false, forKey: "allowOn")
        if lockUntil > Date() { applyShield() }
        call.resolve()
    }

    @objc func isEnabled(_ call: CAPPluginCall) {
        call.resolve(["enabled": authorized && !isEmpty(load("block"))])
    }

    @objc func getStatus(_ call: CAPPluginCall) {
        expireIfDue()
        let left = max(0, lockUntil.timeIntervalSinceNow)
        call.resolve([
            "enabled": authorized && !isEmpty(load("block")),
            "serviceRunning": authorized,
            "locked": left > 0,
            "secondsLeft": Int(left),
            "whatsappAllowed": defaults.bool(forKey: "allowOn"),
            "lastBlocked": "",
            "authorized": authorized,
            "hasBlockList": !isEmpty(load("block")),
            "hasAllowList": !isEmpty(load("allow")),
        ])
    }

    /// Android opens Accessibility settings; on iOS the equivalent is the Screen Time prompt.
    @objc func openSettings(_ call: CAPPluginCall) { requestAuthorization(call) }

    // Friction gate: Android-only. Inert so shared page code keeps working.
    @objc func setFrictionEnabled(_ call: CAPPluginCall) { call.resolve() }
    @objc func getFrictionState(_ call: CAPPluginCall) { call.resolve(["frictionOn": false]) }
    @objc func syncPoints(_ call: CAPPluginCall) { call.resolve() }
    @objc func drainEvents(_ call: CAPPluginCall) { call.resolve(["events": "[]"]) }

    // Google sign-in: open Safari. The token comes back through maatram://auth, handled in native.js.
    @objc func openUrl(_ call: CAPPluginCall) {
        guard let s = call.getString("url"), let u = URL(string: s) else { call.reject("url required"); return }
        DispatchQueue.main.async { UIApplication.shared.open(u) { _ in call.resolve() } }
    }
    @objc func consumeAuthToken(_ call: CAPPluginCall) { call.resolve(["idToken": ""]) }
}

/// SwiftUI wrapper around Apple's picker. nil = cancelled.
struct AppPickerView: View {
    @State private var selection: FamilyActivitySelection
    let title: String
    let done: (FamilyActivitySelection?) -> Void
    init(initial: FamilyActivitySelection, title: String, done: @escaping (FamilyActivitySelection?) -> Void) {
        _selection = State(initialValue: initial); self.title = title; self.done = done
    }
    var body: some View {
        NavigationView {
            FamilyActivityPicker(selection: $selection)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { done(nil) } }
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { done(selection) } }
                }
        }
    }
}
