import Foundation
import CarPlay

/// Fallback CarPlay scene delegate provided by the plugin.
/// For apps that use this plugin, it's recommended to create a CarPlaySceneDelegate
/// in the main app target instead (see README), since iOS requires the delegate
/// class to be in the main binary for reliable scene instantiation.
@available(iOS 14.0, *)
@objc(PluginCarPlaySceneDelegate)
public class PluginCarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {

    private var interfaceController: CPInterfaceController?

    @objc public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        guard CarPlayMediaManager.isEnabled else { return }
        self.interfaceController = interfaceController
        CarPlayMediaManager.shared.interfaceController = interfaceController
        NotificationCenter.default.post(name: Notification.Name("CarPlayDidConnect"), object: interfaceController)
        CarPlayMediaManager.shared.setupRootTemplate()
    }

    @objc public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didDisconnect interfaceController: CPInterfaceController) {
        self.interfaceController = nil
        CarPlayMediaManager.shared.interfaceController = nil
        NotificationCenter.default.post(name: Notification.Name("CarPlayDidDisconnect"), object: nil)
    }
}
