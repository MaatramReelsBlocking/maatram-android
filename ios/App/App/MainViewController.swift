import UIKit
import Capacitor

// Registers the local AppBlocker plugin (Capacitor 6 does not auto-discover app-local plugins).
class MainViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(AppBlockerPlugin())
    }
}
