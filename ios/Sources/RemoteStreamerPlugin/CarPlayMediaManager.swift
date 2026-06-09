import Foundation
import CarPlay

@available(iOS 14.0, *)
public class CarPlayMediaManager {

    public static let shared = CarPlayMediaManager()

    var mediaItems: [[String: Any]]?

    private init() {}

    public func setMediaItems(_ items: [[String: Any]]) {
        self.mediaItems = items
        // Notify CarPlay scene delegate to refresh
        NotificationCenter.default.post(name: Notification.Name("CarPlayTemplateUpdate"), object: nil)
    }

    func buildListTemplate() -> CPListTemplate {
        var listItems: [CPListItem] = []

        guard let items = mediaItems else {
            return CPListTemplate(title: "Live", sections: [])
        }

        for item in items {
            let title = item["title"] as? String ?? ""
            let artist = item["artist"] as? String ?? ""
            let listItem = CPListItem(text: title, detailText: artist)
            listItem.userInfo = item

            // Load artwork asynchronously if available
            if let imageUrlString = item["imageUrl"] as? String, let imageUrl = URL(string: imageUrlString) {
                DispatchQueue.global(qos: .userInitiated).async {
                    if let data = try? Data(contentsOf: imageUrl), let image = UIImage(data: data) {
                        DispatchQueue.main.async {
                            listItem.setImage(image)
                        }
                    }
                }
            }

            listItem.handler = { [weak self] selectedItem, completion in
                guard let userInfo = selectedItem.userInfo as? [String: Any],
                      let streamUrl = userInfo["streamUrl"] as? String else {
                    completion()
                    return
                }
                // Notify plugin to start playback
                NotificationCenter.default.post(
                    name: Notification.Name("CarPlayPlayRequest"),
                    object: nil,
                    userInfo: ["streamUrl": streamUrl, "id": userInfo["id"] ?? ""]
                )
                completion()
            }
            listItems.append(listItem)
        }

        let section = CPListSection(items: listItems)
        return CPListTemplate(title: "Live", sections: [section])
    }
}
