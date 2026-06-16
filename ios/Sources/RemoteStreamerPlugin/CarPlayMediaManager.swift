import Foundation
import CarPlay
import MediaPlayer

@available(iOS 14.0, *)
public class CarPlayMediaManager {

    public static let shared = CarPlayMediaManager()

    // Legacy: media items set from JS (kept for backward compatibility)
    var mediaItems: [[String: Any]]?

    // Interface controller reference (set by scene delegate)
    public weak var interfaceController: CPInterfaceController?

    // Cache of mediaId -> stream URL for playback lookup
    private var browseUriCache: [String: String] = [:]
    // Cache of mediaId -> metadata (title, subtitle, imageUrl, isLive)
    private var browseMetadataCache: [String: (title: String, subtitle: String, imageUrl: String, isLive: Bool)] = [:]

    // Image cache to avoid repeated downloads
    private var imageCache: [String: UIImage] = [:]

    private let fetchQueue = DispatchQueue(label: "co.broadcastapp.carplay.fetch", qos: .userInitiated)

    private init() {
        // Observe CarPlay connection notifications from the app-target scene delegate
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCarPlayDidConnect(_:)),
            name: Notification.Name("CarPlayDidConnect"),
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCarPlayDidDisconnect(_:)),
            name: Notification.Name("CarPlayDidDisconnect"),
            object: nil
        )

        // Handle race condition: CarPlay may have connected before this singleton
        // was initialized. Check connected scenes for an existing CarPlay session.
        DispatchQueue.main.async { [weak self] in
            self?.checkForExistingConnection()
        }
    }

    private func checkForExistingConnection() {
        guard self.interfaceController == nil else { return }
        // Use UIKit's connected scenes API to find an active CarPlay scene
        if let carPlayScene = UIApplication.shared.connectedScenes.first(where: {
            $0.session.role.rawValue == "CPTemplateApplicationSceneSessionRoleApplication"
        }) as? CPTemplateApplicationScene {
            self.interfaceController = carPlayScene.interfaceController
            setupRootTemplate()
        }
    }

    @objc private func handleCarPlayDidConnect(_ notification: Notification) {
        guard let controller = notification.object as? CPInterfaceController else { return }
        self.interfaceController = controller
        setupRootTemplate()
    }

    @objc private func handleCarPlayDidDisconnect(_ notification: Notification) {
        self.interfaceController = nil
    }

    // MARK: - Public Interface

    /// Legacy method for JS-driven media items
    public func setMediaItems(_ items: [[String: Any]]) {
        self.mediaItems = items
        NotificationCenter.default.post(name: Notification.Name("CarPlayTemplateUpdate"), object: nil)
    }

    /// Build and set the root tab bar template with 4 tabs
    public func setupRootTemplate() {
        guard let controller = interfaceController else { return }

        // Create tab templates — start with loading placeholders, then populate async
        let liveTab = buildLoadingTemplate(title: "Live Radio", systemImageName: "antenna.radiowaves.left.and.right")
        let newsTab = buildLoadingTemplate(title: "Latest News", systemImageName: "newspaper")
        let storiesTab = buildLoadingTemplate(title: "Top Stories", systemImageName: "star")
        let showsTab = buildLoadingTemplate(title: "All Shows", systemImageName: "music.mic")

        let tabBar = CPTabBarTemplate(templates: [liveTab, newsTab, storiesTab, showsTab])
        controller.setRootTemplate(tabBar, animated: true, completion: nil)

        // Load data for each tab asynchronously
        fetchQueue.async { [weak self] in
            self?.loadLiveStreams(into: tabBar, tabIndex: 0)
        }
        fetchQueue.async { [weak self] in
            self?.loadLatestNews(into: tabBar, tabIndex: 1)
        }
        fetchQueue.async { [weak self] in
            self?.loadTopStories(into: tabBar, tabIndex: 2)
        }
        fetchQueue.async { [weak self] in
            self?.loadAllShows(into: tabBar, tabIndex: 3)
        }
    }

    /// Look up stream URL for a given mediaId
    func getStreamUrl(for mediaId: String) -> String? {
        return browseUriCache[mediaId]
    }

    /// Get cached metadata for a given mediaId
    func getMetadata(for mediaId: String) -> (title: String, subtitle: String, imageUrl: String, isLive: Bool)? {
        return browseMetadataCache[mediaId]
    }

    // MARK: - Tab Builders

    private func buildLoadingTemplate(title: String, systemImageName: String) -> CPListTemplate {
        let template = CPListTemplate(title: title, sections: [])
        if let image = UIImage(systemName: systemImageName) {
            template.tabImage = image
        }
        return template
    }

    private func loadLiveStreams(into tabBar: CPTabBarTemplate, tabIndex: Int) {
        let streams = BffApiClient.shared.fetchLiveStreams()
        var listItems: [CPListItem] = []

        for stream in streams {
            let mediaId = "live_\(stream.slug)"
            let subtitle = stream.currentShowTitle.isEmpty ? "Live" : stream.currentShowTitle

            browseUriCache[mediaId] = stream.hlsUrl
            browseMetadataCache[mediaId] = (title: stream.stationName, subtitle: subtitle, imageUrl: stream.imageUrl, isLive: true)

            let item = CPListItem(text: stream.stationName, detailText: subtitle)
            item.accessoryType = .none
            item.userInfo = ["mediaId": mediaId] as [String: Any]
            item.handler = { [weak self] _, completion in
                self?.handlePlayRequest(mediaId: mediaId)
                completion()
            }

            // Load artwork
            loadImage(from: stream.imageUrl) { image in
                if let image = image {
                    item.setImage(image)
                }
            }

            listItems.append(item)
        }

        let section = CPListSection(items: listItems)
        DispatchQueue.main.async {
            let template = CPListTemplate(title: "Live Radio", sections: [section])
            if let image = UIImage(systemName: "antenna.radiowaves.left.and.right") {
                template.tabImage = image
            }
            self.updateTab(tabBar, at: tabIndex, with: template)
        }
    }

    private func loadLatestNews(into tabBar: CPTabBarTemplate, tabIndex: Int) {
        let news = BffApiClient.shared.fetchLatestNews()
        var listItems: [CPListItem] = []

        for newsItem in news {
            let mediaId = "news_\(newsItem.id)"
            var subtitle = newsItem.showTitle
            if newsItem.durationSeconds > 0 {
                subtitle += " | \(newsItem.durationSeconds / 60) min"
            }

            browseUriCache[mediaId] = newsItem.audioUrl
            browseMetadataCache[mediaId] = (title: newsItem.title, subtitle: subtitle, imageUrl: newsItem.imageUrl, isLive: false)

            let item = CPListItem(text: newsItem.title, detailText: subtitle)
            item.accessoryType = .none
            item.userInfo = ["mediaId": mediaId] as [String: Any]
            item.handler = { [weak self] _, completion in
                self?.handlePlayRequest(mediaId: mediaId)
                completion()
            }

            loadImage(from: newsItem.imageUrl) { image in
                if let image = image {
                    item.setImage(image)
                }
            }

            listItems.append(item)
        }

        let section = CPListSection(items: listItems)
        DispatchQueue.main.async {
            let template = CPListTemplate(title: "Latest News", sections: [section])
            if let image = UIImage(systemName: "newspaper") {
                template.tabImage = image
            }
            self.updateTab(tabBar, at: tabIndex, with: template)
        }
    }

    private func loadTopStories(into tabBar: CPTabBarTemplate, tabIndex: Int) {
        let stories = BffApiClient.shared.fetchTopStories()
        var listItems: [CPListItem] = []

        for story in stories {
            let mediaId = "story_\(story.id)"
            var subtitle = story.showTitle
            if story.durationSeconds > 0 {
                subtitle += " | \(story.durationSeconds / 60) min"
            }

            browseUriCache[mediaId] = story.audioUrl
            browseMetadataCache[mediaId] = (title: story.title, subtitle: subtitle, imageUrl: story.imageUrl, isLive: false)

            let item = CPListItem(text: story.title, detailText: subtitle)
            item.accessoryType = .none
            item.userInfo = ["mediaId": mediaId] as [String: Any]
            item.handler = { [weak self] _, completion in
                self?.handlePlayRequest(mediaId: mediaId)
                completion()
            }

            loadImage(from: story.imageUrl) { image in
                if let image = image {
                    item.setImage(image)
                }
            }

            listItems.append(item)
        }

        let section = CPListSection(items: listItems)
        DispatchQueue.main.async {
            let template = CPListTemplate(title: "Top Stories", sections: [section])
            if let image = UIImage(systemName: "star") {
                template.tabImage = image
            }
            self.updateTab(tabBar, at: tabIndex, with: template)
        }
    }

    private func loadAllShows(into tabBar: CPTabBarTemplate, tabIndex: Int) {
        let shows = BffApiClient.shared.fetchAllShows()
        var listItems: [CPListItem] = []

        for show in shows {
            let item = CPListItem(text: show.title, detailText: nil)
            item.accessoryType = .disclosureIndicator
            item.userInfo = ["showSlug": show.slug] as [String: Any]
            item.handler = { [weak self] _, completion in
                self?.showEpisodes(for: show.slug, showTitle: show.title)
                completion()
            }

            loadImage(from: show.imageUrl) { image in
                if let image = image {
                    item.setImage(image)
                }
            }

            listItems.append(item)
        }

        let section = CPListSection(items: listItems)
        DispatchQueue.main.async {
            let template = CPListTemplate(title: "All Shows", sections: [section])
            if let image = UIImage(systemName: "music.mic") {
                template.tabImage = image
            }
            self.updateTab(tabBar, at: tabIndex, with: template)
        }
    }

    // MARK: - Episodes (drill-down from a show)

    private func showEpisodes(for showSlug: String, showTitle: String) {
        guard let controller = interfaceController else { return }

        // Show a loading template while we fetch episodes
        let loadingTemplate = CPListTemplate(title: showTitle, sections: [])
        controller.pushTemplate(loadingTemplate, animated: true, completion: nil)

        fetchQueue.async { [weak self] in
            guard let self = self else { return }
            let episodes = BffApiClient.shared.fetchEpisodes(showSlug: showSlug)
            var listItems: [CPListItem] = []

            for ep in episodes {
                let mediaId = "episode_\(ep.id)"
                var subtitle = ep.showTitle
                if ep.durationSeconds > 0 {
                    subtitle += " | \(ep.durationSeconds / 60) min"
                }

                self.browseUriCache[mediaId] = ep.audioUrl
                self.browseMetadataCache[mediaId] = (title: ep.title, subtitle: subtitle, imageUrl: ep.imageUrl, isLive: false)

                let item = CPListItem(text: ep.title, detailText: subtitle)
                item.accessoryType = .none
                item.userInfo = ["mediaId": mediaId] as [String: Any]
                item.handler = { [weak self] _, completion in
                    self?.handlePlayRequest(mediaId: mediaId)
                    completion()
                }

                self.loadImage(from: ep.imageUrl) { image in
                    if let image = image {
                        item.setImage(image)
                    }
                }

                listItems.append(item)
            }

            let section = CPListSection(items: listItems)
            DispatchQueue.main.async {
                loadingTemplate.updateSections([section])
            }
        }
    }

    // MARK: - Playback

    private func handlePlayRequest(mediaId: String) {
        guard let streamUrl = browseUriCache[mediaId] else { return }
        let metadata = browseMetadataCache[mediaId]

        // Enable remote transport controls
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.playCommand.isEnabled = true
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.isEnabled = true

        let isLive = metadata?.isLive ?? streamUrl.contains(".m3u8")
        if !isLive {
            commandCenter.skipForwardCommand.isEnabled = true
            commandCenter.skipBackwardCommand.isEnabled = true
        } else {
            commandCenter.skipForwardCommand.isEnabled = false
            commandCenter.skipBackwardCommand.isEnabled = false
        }

        // Update Now Playing info
        if let metadata = metadata {
            updateNowPlaying(title: metadata.title, subtitle: metadata.subtitle, imageUrl: metadata.imageUrl, isLive: isLive)
        }

        // Notify the plugin to start playback
        NotificationCenter.default.post(
            name: Notification.Name("CarPlayPlayRequest"),
            object: nil,
            userInfo: [
                "streamUrl": streamUrl,
                "id": mediaId,
                "isLive": isLive
            ]
        )
    }

    private func updateNowPlaying(title: String, subtitle: String, imageUrl: String, isLive: Bool) {
        var nowPlayingInfo = [String: Any]()
        nowPlayingInfo[MPMediaItemPropertyTitle] = title
        nowPlayingInfo[MPMediaItemPropertyArtist] = subtitle
        nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = isLive
        if !isLive {
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = 0
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo

        // Load artwork asynchronously
        if !imageUrl.isEmpty, let url = URL(string: imageUrl) {
            DispatchQueue.global(qos: .userInitiated).async {
                if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
                    let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    DispatchQueue.main.async {
                        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                        info[MPMediaItemPropertyArtwork] = artwork
                        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
                    }
                }
            }
        }
    }

    // MARK: - Helpers

    private func updateTab(_ tabBar: CPTabBarTemplate, at index: Int, with template: CPListTemplate) {
        var templates = tabBar.templates
        guard index < templates.count else { return }
        templates[index] = template
        tabBar.updateTemplates(templates)
    }

    private func loadImage(from urlString: String, completion: @escaping (UIImage?) -> Void) {
        guard !urlString.isEmpty, let url = URL(string: urlString) else {
            completion(nil)
            return
        }

        // Check cache first
        if let cached = imageCache[urlString] {
            DispatchQueue.main.async { completion(cached) }
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            // Cache it
            self?.imageCache[urlString] = image
            DispatchQueue.main.async { completion(image) }
        }
    }

    // MARK: - Legacy Support

    /// Legacy method kept for backward compatibility — builds a simple list from JS-provided items
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

