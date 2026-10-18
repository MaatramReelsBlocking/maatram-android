import ManagedSettings
import ManagedSettingsUI
import UIKit

/// The screen shown over a locked app.
class ShieldConfigurationExtension: ShieldConfigurationDataSource {
    private func shield() -> ShieldConfiguration {
        ShieldConfiguration(
            backgroundBlurStyle: .systemUltraThinMaterialDark,
            backgroundColor: UIColor(red: 0.016, green: 0.071, blue: 0.059, alpha: 1),
            title: ShieldConfiguration.Label(text: "Maatram Hard Lock", color: .white),
            subtitle: ShieldConfiguration.Label(text: "This app is locked while your session runs. Go back to focus.",
                                                color: UIColor(white: 1, alpha: 0.7)))
    }
    override func configuration(shielding application: Application) -> ShieldConfiguration { shield() }
    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration { shield() }
    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration { shield() }
    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration { shield() }
}
