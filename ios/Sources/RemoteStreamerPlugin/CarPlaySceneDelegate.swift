import Foundation
import CarPlay

@available(iOS 14.0, *)
public class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {

    private var interfaceController: CPInterfaceController?
    private var listTemplate: CPListTemplate?

    public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController

        // Post notification so the plugin knows CarPlay connected
        NotificationCenter.default.post(name: Notification.Name("CarPlayDidConnect"), object: interfaceController)

        // If media items were already set, build the template
        if let items = CarPlayMediaManager.shared.mediaItems, !items.isEmpty {
            let template = CarPlayMediaManager.shared.buildListTemplate()
            self.listTemplate = template
            interfaceController.setRootTemplate(template, animated: true, completion: nil)
        } else {
            // Show an empty template that will be updated when items arrive
            let template = CPListTemplate(title: "Live", sections: [])
            self.listTemplate = template
            interfaceController.setRootTemplate(template, animated: true, completion: nil)
        }

        // Listen for template updates
        NotificationCenter.default.addObserver(self, selector: #selector(handleTemplateUpdate), name: Notification.Name("CarPlayTemplateUpdate"), object: nil)
    }

    public func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene, didDisconnect interfaceController: CPInterfaceController) {
        self.interfaceController = nil
        NotificationCenter.default.removeObserver(self, name: Notification.Name("CarPlayTemplateUpdate"), object: nil)
        NotificationCenter.default.post(name: Notification.Name("CarPlayDidDisconnect"), object: nil)
    }

    @objc private func handleTemplateUpdate() {
        guard let controller = interfaceController else { return }
        let template = CarPlayMediaManager.shared.buildListTemplate()
        self.listTemplate = template
        controller.setRootTemplate(template, animated: true, completion: nil)
    }
}
