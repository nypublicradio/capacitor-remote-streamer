import Foundation
import CarPlay
import MediaPlayer

@available(iOS 14.0, *)
public class CarPlayMediaManager: NSObject {

    public static let shared = CarPlayMediaManager()

    // Legacy: media items set from JS (kept for backward compatibility)
    var mediaItems: [[String: Any]]?

    // Interface controller reference (set by scene delegate)
    public weak var interfaceController: CPInterfaceController?

    // Cache of mediaId -> stream URL for playback lookup
    private var browseUriCache: [String: String] = [:]
    // Cache of mediaId -> metadata (title, subtitle, imageUrl, isLive, durationSeconds)
    private var browseMetadataCache: [String: (title: String, subtitle: String, imageUrl: String, isLive: Bool, durationSeconds: Int)] = [:]

    // Image cache to avoid repeated downloads (accessed from multiple threads)
    private var imageCache: [String: UIImage] = [:]
    private let imageCacheLock = NSLock()

    private let fetchQueue = DispatchQueue(label: "co.broadcastapp.carplay.fetch", qos: .userInitiated)

    // Metadata waiting to be applied to Now Playing when playback actually starts
    var pendingNowPlayingMetadata: (title: String, subtitle: String, imageUrl: String, isLive: Bool, durationSeconds: Int)?

    // All shows (including "The" alternates) for search filtering
    private var allShowsForSearch: [(title: String, slug: String, imageUrl: String)] = []

    private override init() {
        super.init()
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
        let newsTab = buildLoadingTemplate(title: "News", systemImageName: "newspaper")
        let storiesTab = buildLoadingTemplate(title: "Top Stories", systemImageName: "star")
        let showsTab = buildLoadingTemplate(title: "Shows", systemImageName: "music.mic")

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
    func getMetadata(for mediaId: String) -> (title: String, subtitle: String, imageUrl: String, isLive: Bool, durationSeconds: Int)? {
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
            browseMetadataCache[mediaId] = (title: stream.stationName, subtitle: subtitle, imageUrl: stream.imageUrl, isLive: true, durationSeconds: 0)

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
            browseMetadataCache[mediaId] = (title: newsItem.title, subtitle: newsItem.showTitle, imageUrl: newsItem.imageUrl, isLive: false, durationSeconds: newsItem.durationSeconds)

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
            let template = CPListTemplate(title: "News", sections: [section])
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
            browseMetadataCache[mediaId] = (title: story.title, subtitle: story.showTitle, imageUrl: story.imageUrl, isLive: false, durationSeconds: story.durationSeconds)

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
        let allShows = BffApiClient.shared.fetchAllShows()
        let featuredShows = BffApiClient.shared.fetchFeaturedShows()

        // Build image lookup map from allShows (featured shows lack image URLs)
        var showImageMap: [String: String] = [:]
        for show in allShows {
            showImageMap[show.slug] = show.imageUrl
        }

        // 1. Featured Shows items (at the top, unsorted)
        var featuredItems: [CPListItem] = []
        for show in featuredShows {
            let imageUrl = showImageMap[show.slug] ?? show.imageUrl
            let item = CPListItem(text: show.title, detailText: nil)
            item.accessoryType = .disclosureIndicator
            item.userInfo = ["showSlug": show.slug] as [String: Any]
            item.handler = { [weak self] _, completion in
                self?.showEpisodes(for: show.slug, showTitle: show.title)
                completion()
            }

            loadImage(from: imageUrl) { image in
                if let image = image {
                    item.setImage(image)
                }
            }

            featuredItems.append(item)
        }

        // 2. All Shows items with "The" prefix alternates
        var allItems: [(title: String, item: CPListItem)] = []
        for show in allShows {
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

            allItems.append((title: show.title, item: item))

            // Duplicate shows starting with "The " under their alternate letter
            if show.title.lowercased().hasPrefix("the ") {
                let altTitle = String(show.title.dropFirst(4)) + ", " + String(show.title.prefix(3))
                let altItem = CPListItem(text: altTitle, detailText: nil)
                altItem.accessoryType = .disclosureIndicator
                altItem.userInfo = ["showSlug": show.slug] as [String: Any]
                altItem.handler = { [weak self] _, completion in
                    self?.showEpisodes(for: show.slug, showTitle: show.title)
                    completion()
                }

                loadImage(from: show.imageUrl) { image in
                    if let image = image {
                        altItem.setImage(image)
                    }
                }

                allItems.append((title: altTitle, item: altItem))
            }
        }

        // Sort all shows alphabetically (case-insensitive)
        allItems.sort { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }

        // Combine: featured first, then sorted all shows
        let combinedItems = featuredItems + allItems.map { $0.item }

        // Store all shows for search filtering (deduplicated by title)
        var searchEntries: [(title: String, slug: String, imageUrl: String)] = []
        var seenTitles = Set<String>()
        // Add featured shows first
        for show in featuredShows {
            let imageUrl = showImageMap[show.slug] ?? show.imageUrl
            if seenTitles.insert(show.title.lowercased()).inserted {
                searchEntries.append((title: show.title, slug: show.slug, imageUrl: imageUrl))
            }
        }
        // Add all shows (including "The" alternates)
        for show in allShows {
            if seenTitles.insert(show.title.lowercased()).inserted {
                searchEntries.append((title: show.title, slug: show.slug, imageUrl: show.imageUrl))
            }
            if show.title.lowercased().hasPrefix("the ") {
                let altTitle = String(show.title.dropFirst(4)) + ", " + String(show.title.prefix(3))
                if seenTitles.insert(altTitle.lowercased()).inserted {
                    searchEntries.append((title: altTitle, slug: show.slug, imageUrl: show.imageUrl))
                }
            }
        }
        self.allShowsForSearch = searchEntries

        let section = CPListSection(items: combinedItems)
        DispatchQueue.main.async {
            let template = CPListTemplate(title: "Shows", sections: [section])
            if let image = UIImage(systemName: "music.mic") {
                template.tabImage = image
            }

            // Add search support via assistant bar button
            let searchButton = CPBarButton(title: "Search") { [weak self] _ in
                self?.showSearchTemplate()
            }
            template.trailingNavigationBarButtons = [searchButton]

            self.updateTab(tabBar, at: tabIndex, with: template)
        }
    }

    // MARK: - Search

    private func showSearchTemplate() {
        guard let controller = interfaceController else { return }

        let searchTemplate = CPSearchTemplate()
        searchTemplate.delegate = self
        controller.pushTemplate(searchTemplate, animated: true, completion: nil)
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
                self.browseMetadataCache[mediaId] = (title: ep.title, subtitle: ep.showTitle, imageUrl: ep.imageUrl, isLive: false, durationSeconds: ep.durationSeconds)

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

        let isLive = metadata?.isLive ?? streamUrl.contains(".m3u8")

        // Enable command center via the single owner (RemoteStreamer)
        RemoteStreamer.shared.enableCommandCenter(seekEnabled: !isLive)

        // Play directly via the shared RemoteStreamer — works even if the plugin isn't loaded
        RemoteStreamer.shared.play(url: streamUrl) { _ in }

        // Set playback state
        MPNowPlayingInfoCenter.default().playbackState = .playing

        // Set Now Playing metadata
        if let metadata = metadata {
            var nowPlayingInfo = [String: Any]()
            nowPlayingInfo[MPMediaItemPropertyTitle] = metadata.title
            nowPlayingInfo[MPMediaItemPropertyArtist] = metadata.subtitle
            nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = metadata.isLive
            nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
            nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = 0.0
            if !metadata.isLive && metadata.durationSeconds > 0 {
                nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = Double(metadata.durationSeconds)
            }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo

            // Load artwork asynchronously
            if !metadata.imageUrl.isEmpty {
                loadImage(from: metadata.imageUrl) { image in
                    if let image = image {
                        let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                        info[MPMediaItemPropertyArtwork] = artwork
                        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
                    }
                }
            }
        }

        // Also notify the plugin (if loaded) so JS listeners fire
        NotificationCenter.default.post(
            name: Notification.Name("CarPlayPlayRequest"),
            object: nil,
            userInfo: [
                "streamUrl": streamUrl,
                "id": mediaId,
                "isLive": isLive
            ]
        )

        // Navigate to Now Playing screen
        DispatchQueue.main.async { [weak self] in
            if let controller = self?.interfaceController {
                let nowPlayingTemplate = CPNowPlayingTemplate.shared
                if !(controller.topTemplate is CPNowPlayingTemplate) {
                    controller.pushTemplate(nowPlayingTemplate, animated: true, completion: nil)
                }
            }
        }
    }

    private func updateNowPlaying(title: String, subtitle: String, imageUrl: String, isLive: Bool, durationSeconds: Int = 0) {
        var nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [String: Any]()
        nowPlayingInfo[MPMediaItemPropertyTitle] = title
        nowPlayingInfo[MPMediaItemPropertyArtist] = subtitle
        nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = isLive
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
        if !isLive && durationSeconds > 0 {
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = Double(durationSeconds)
            nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo

        // Load artwork asynchronously and update
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

        // Check cache first (thread-safe)
        imageCacheLock.lock()
        let cached = imageCache[urlString]
        imageCacheLock.unlock()
        if let cached = cached {
            DispatchQueue.main.async { completion(cached) }
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            // Cache it (thread-safe)
            self.imageCacheLock.lock()
            self.imageCache[urlString] = image
            self.imageCacheLock.unlock()
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

// MARK: - CPSearchTemplateDelegate

@available(iOS 14.0, *)
extension CarPlayMediaManager: CPSearchTemplateDelegate {
    public func searchTemplate(_ searchTemplate: CPSearchTemplate, updatedSearchText searchText: String, completionHandler: @escaping ([CPListItem]) -> Void) {
        guard !searchText.isEmpty else {
            completionHandler([])
            return
        }

        let query = searchText.lowercased()
        let filtered = allShowsForSearch.filter { $0.title.lowercased().contains(query) }

        // Sort: prefix matches first, then contains matches
        let sorted = filtered.sorted { a, b in
            let aPrefix = a.title.lowercased().hasPrefix(query)
            let bPrefix = b.title.lowercased().hasPrefix(query)
            if aPrefix != bPrefix { return aPrefix }
            return a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending
        }

        var results: [CPListItem] = []
        for show in sorted {
            let item = CPListItem(text: show.title, detailText: nil)
            item.accessoryType = .disclosureIndicator
            item.userInfo = ["showSlug": show.slug, "showTitle": show.title] as [String: Any]
            item.handler = { [weak self] listItem, completion in
                guard let info = listItem.userInfo as? [String: Any],
                      let slug = info["showSlug"] as? String,
                      let title = info["showTitle"] as? String else {
                    completion()
                    return
                }
                self?.showEpisodes(for: slug, showTitle: title)
                completion()
            }

            loadImage(from: show.imageUrl) { image in
                if let image = image {
                    item.setImage(image)
                }
            }

            results.append(item)
        }

        completionHandler(results)
    }

    public func searchTemplateSearchButtonPressed(_ searchTemplate: CPSearchTemplate) {
        // No additional action needed — results are updated live as the user types
    }

    public func searchTemplate(_ searchTemplate: CPSearchTemplate, selectedResult item: CPListItem) async {
        guard let info = item.userInfo as? [String: Any],
              let slug = info["showSlug"] as? String,
              let title = info["showTitle"] as? String else {
            return
        }

        showEpisodes(for: slug, showTitle: title)
    }
}

