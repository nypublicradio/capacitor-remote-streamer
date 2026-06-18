import Foundation

/// Native HTTP client for fetching media browse data from the WNYC BFF API.
/// Used by CarPlay to populate the browse tree — mirrors the Android BffApiClient.
@available(iOS 14.0, *)
class BffApiClient {
    static let shared = BffApiClient()

    private var baseUrl = "https://wnyc.org"
    private var aviaryBaseUrl = "https://cms.nypr.digital/api/v2"
    private let session: URLSession
    private let timeoutInterval: TimeInterval = 10

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeoutInterval
        config.timeoutIntervalForResource = timeoutInterval
        session = URLSession(configuration: config)
    }

    func setBaseUrl(_ url: String) {
        baseUrl = url
    }

    // MARK: - Data Models

    struct LiveStream {
        let slug: String
        let stationName: String
        let currentShowTitle: String
        let hlsUrl: String
        let imageUrl: String
    }

    struct NewsItem {
        let id: String
        let title: String
        let showTitle: String
        let audioUrl: String
        let imageUrl: String
        let durationSeconds: Int
    }

    struct StoryItem {
        let id: String
        let title: String
        let showTitle: String
        let audioUrl: String
        let imageUrl: String
        let durationSeconds: Int
    }

    struct Show {
        let slug: String
        let title: String
        let imageUrl: String
    }

    struct Episode {
        let id: String
        let title: String
        let showTitle: String
        let audioUrl: String
        let imageUrl: String
        let durationSeconds: Int
    }

    // MARK: - API Methods

    /// Fetch live streams from /api/streams
    func fetchLiveStreams() -> [LiveStream] {
        guard let json = httpGetSync(baseUrl + "/api/streams"),
              let arr = try? JSONSerialization.jsonObject(with: json) as? [[String: Any]] else {
            return []
        }

        var streams: [LiveStream] = []
        for obj in arr {
            let slug = obj["slug"] as? String ?? ""
            let stationName = obj["station"] as? String ?? ""
            let currentShow = obj["title"] as? String ?? obj["showTitle"] as? String ?? ""

            var imageUrl = obj["image"] as? String ?? ""
            if imageUrl.isEmpty {
                if let stationImage = obj["stationImage"] as? [String: Any] {
                    let template = stationImage["template"] as? String ?? ""
                    if !template.isEmpty {
                        imageUrl = template.replacingOccurrences(of: "%s/%s/%s/%s", with: "512/512/c/80")
                    } else {
                        imageUrl = stationImage["url"] as? String ?? ""
                    }
                }
            }

            var hlsUrl = obj["hls"] as? String ?? ""
            if hlsUrl.isEmpty {
                hlsUrl = obj["audio"] as? String ?? obj["file"] as? String ?? ""
            }

            if !hlsUrl.isEmpty {
                streams.append(LiveStream(slug: slug, stationName: stationName, currentShowTitle: currentShow, hlsUrl: hlsUrl, imageUrl: imageUrl))
            }
        }
        return streams
    }

    /// Fetch latest news from /api/homepagelatestnewsupdates
    func fetchLatestNews() -> [NewsItem] {
        guard let json = httpGetSync(baseUrl + "/api/homepagelatestnewsupdates"),
              let obj = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            return []
        }

        var news: [NewsItem] = []

        // Local newscast (NYC Headlines)
        let local = obj["local_newscast"] as? [String: Any] ?? obj["localNewscast"] as? [String: Any]
        if let local = local, let item = parseNewsItem(local, fallbackId: "local_newscast") {
            news.append(item)
        }

        // National newscast (NPR News Now)
        let national = obj["national_newscast"] as? [String: Any] ?? obj["nationalNewscast"] as? [String: Any]
        if let national = national, let item = parseNewsItem(national, fallbackId: "national_newscast") {
            news.append(item)
        }

        return news
    }

    /// Fetch top stories from /api/curated_lists/87
    func fetchTopStories() -> [StoryItem] {
        guard let json = httpGetSync(baseUrl + "/api/curated_lists/87"),
              let obj = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            return []
        }

        let listItems = obj["listItems"] as? [[String: Any]] ?? obj["list_items"] as? [[String: Any]] ?? []

        var stories: [StoryItem] = []
        for item in listItems {
            guard stories.count < 4 else { break }

            let hasAudio = item["hasAudio"] as? Bool ?? false
            guard hasAudio else { continue }

            let audioUrl = item["audio"] as? String ?? ""
            guard !audioUrl.isEmpty else { continue }

            let id = item["id"] as? String ?? "story_\(stories.count)"
            let title = item["title"] as? String ?? ""
            let showTitle = item["showTitle"] as? String ?? item["show_title"] as? String ?? ""
            let duration = item["estimatedDuration"] as? Int ?? item["estimated_duration"] as? Int ?? 0
            let imageUrl = resolveImageUrl(from: item, includeBrandFallback: true, keys: "image")

            stories.append(StoryItem(id: id, title: title, showTitle: showTitle, audioUrl: audioUrl, imageUrl: imageUrl, durationSeconds: duration))
        }
        return stories
    }

    /// Fetch all shows from /api/v3/shows
    func fetchAllShows() -> [Show] {
        guard let json = httpGetSync(baseUrl + "/api/v3/shows"),
              let obj = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            return []
        }

        guard let allShows = obj["all"] as? [[String: Any]] else { return [] }

        var shows: [Show] = []
        for showObj in allShows {
            let slug = showObj["slug"] as? String ?? showObj["id"] as? String ?? ""
            var title = showObj["title"] as? String ?? ""
            var imageUrl = resolveImageUrl(from: showObj, includeBrandFallback: false, keys: "image", "showArt", "logoImage", "logo_image")

            if title.isEmpty || imageUrl.isEmpty {
                if let attrs = showObj["attributes"] as? [String: Any] {
                    if title.isEmpty {
                        title = attrs["title"] as? String ?? ""
                    }
                    if imageUrl.isEmpty {
                        imageUrl = resolveImageUrl(from: attrs, includeBrandFallback: false, keys: "image", "image-main", "imageMain")
                    }
                }
            }

            if !title.isEmpty {
                shows.append(Show(slug: slug, title: title, imageUrl: imageUrl))
            }
        }
        return shows
    }

    /// Fetch episodes for a show
    func fetchEpisodes(showSlug: String) -> [Episode] {
        // Step 1: Fetch show page to get podcastId from linkedDataSource
        guard let showJson = httpGetSync(baseUrl + "/api/pages/wagtail/" + showSlug + "?showOnly=true"),
              let showObj = try? JSONSerialization.jsonObject(with: showJson) as? [String: Any] else {
            return []
        }

        guard let linkedDataSource = showObj["linkedDataSource"] as? [[String: Any]],
              let firstSource = linkedDataSource.first,
              let value = firstSource["value"] as? [String: Any],
              let podcastId = value["id"] as? String, !podcastId.isEmpty else {
            return []
        }

        // Step 2: Fetch episodes using the podcastId
        guard let json = httpGetSync(baseUrl + "/api/v3/show/" + podcastId + "/episodes?limit=20"),
              let obj = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            return []
        }

        let data = obj["data"] as? [[String: Any]] ?? []

        var episodes: [Episode] = []
        for ep in data {
            let attrs = ep["attributes"] as? [String: Any] ?? ep
            let id = ep["id"] as? String ?? "ep_\(episodes.count)"
            let title = attrs["title"] as? String ?? ep["title"] as? String ?? ""
            let showTitle = attrs["show_title"] as? String ?? attrs["showTitle"] as? String ?? ep["showTitle"] as? String ?? ""
            let audioUrl = attrs["audio"] as? String ?? attrs["file"] as? String ?? ep["audio"] as? String ?? ep["file"] as? String ?? ""
            var imageUrl = resolveImageUrl(from: attrs, includeBrandFallback: false, keys: "image", "showArt", "logoImage", "logo_image")
            if imageUrl.isEmpty {
                imageUrl = resolveImageUrl(from: ep, includeBrandFallback: false, keys: "image", "showArt", "logoImage", "logo_image")
            }
            let duration = attrs["duration"] as? Int ?? attrs["estimated_duration"] as? Int ?? 0

            if !audioUrl.isEmpty {
                episodes.append(Episode(id: id, title: title, showTitle: showTitle, audioUrl: audioUrl, imageUrl: imageUrl, durationSeconds: duration))
            }
        }
        return episodes
    }

    // MARK: - Helpers

    private func parseNewsItem(_ obj: [String: Any], fallbackId: String) -> NewsItem? {
        let id = obj["id"] as? String ?? fallbackId
        let title = obj["card_title"] as? String ?? obj["cardTitle"] as? String ?? obj["title"] as? String ?? ""
        let showTitle = obj["show_title"] as? String ?? obj["showTitle"] as? String ?? ""
        let audioUrl = obj["file"] as? String ?? obj["audio"] as? String ?? ""
        var imageUrl = ""
        if let headers = obj["headers"] as? [String: Any],
           let brand = headers["brand"] as? [String: Any],
           let logoImage = brand["logoImage"] as? [String: Any] {
            imageUrl = normalizeImageUrl(logoImage["url"] as? String ?? "")
        }
        let duration = obj["duration"] as? Int ?? obj["estimated_duration"] as? Int ?? 0

        guard !audioUrl.isEmpty else { return nil }
        return NewsItem(id: id, title: title, showTitle: showTitle, audioUrl: audioUrl, imageUrl: imageUrl, durationSeconds: duration)
    }

    private func resolveImageUrl(from container: [String: Any], includeBrandFallback: Bool, keys: String...) -> String {
        for key in keys {
            let candidate = resolveImageCandidate(container[key])
            if !candidate.isEmpty { return candidate }
        }
        if includeBrandFallback {
            return resolveBrandLogoUrl(from: container)
        }
        return ""
    }

    private func resolveImageCandidate(_ imageObj: Any?) -> String {
        if let image = imageObj as? [String: Any] {
            var imageUrl = image["file"] as? String ?? image["url"] as? String ?? ""
            if imageUrl.isEmpty {
                let template = image["template"] as? String ?? ""
                if !template.isEmpty {
                    imageUrl = template.replacingOccurrences(of: "%s/%s/%s/%s", with: "200/200/c/80")
                }
            }
            return normalizeImageUrl(imageUrl)
        }
        if let imageStr = imageObj as? String {
            return normalizeImageUrl(imageStr)
        }
        return ""
    }

    private func resolveBrandLogoUrl(from container: [String: Any]) -> String {
        guard let headers = container["headers"] as? [String: Any],
              let brand = headers["brand"] as? [String: Any] else { return "" }
        let logoImage = brand["logoImage"] as? [String: Any] ?? brand["logo_image"] as? [String: Any]
        guard let logo = logoImage else { return "" }
        return normalizeImageUrl(logo["url"] as? String ?? "")
    }

    private func normalizeImageUrl(_ imageUrl: String) -> String {
        guard !imageUrl.isEmpty else { return "" }
        if imageUrl.contains("npr.brightspotcdn.com") {
            return imageUrl
                .replacingOccurrences(of: "{width}", with: "512")
                .replacingOccurrences(of: "{quality}", with: "80")
                .replacingOccurrences(of: "{format}", with: "jpg")
        }
        return imageUrl
    }

    // MARK: - HTTP

    private func httpGetSync(_ urlStr: String) -> Data? {
        guard let url = URL(string: urlStr) else { return nil }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = timeoutInterval

        var result: Data?
        let semaphore = DispatchSemaphore(value: 0)

        let task = session.dataTask(with: request) { data, response, error in
            if let error = error {
                print("BffApiClient: HTTP request failed: \(urlStr) - \(error.localizedDescription)")
                semaphore.signal()
                return
            }
            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? -1
                print("BffApiClient: HTTP \(code) from \(urlStr)")
                semaphore.signal()
                return
            }
            result = data
            semaphore.signal()
        }
        task.resume()
        semaphore.wait()
        return result
    }
}
