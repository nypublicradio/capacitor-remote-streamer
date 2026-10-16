import Foundation

/// Something the player can play, plus the metadata shown on the lock screen,
/// Control Center and CarPlay's Now Playing screen.
///
/// Native code (for example a CarPlay extension) passes one of these to
/// `RemoteStreamer.shared.play(item:)`.
public struct PlayableItem: Equatable {
    /// Stable identifier for the content (reported to JS as `mediaId`).
    public let mediaId: String?
    /// The URL the player actually requests.
    public let url: String
    /// The URL reported back to JS. Use this when `url` carries extra
    /// per-request parameters the app adds on its own. Defaults to `url`.
    public let canonicalUrl: String?
    public let title: String
    public let artist: String
    public let album: String
    public let artworkUrl: String?
    public let isLive: Bool
    /// Known duration in seconds, or 0 when unknown (live streams).
    public let durationSeconds: Double

    public init(
        mediaId: String? = nil,
        url: String,
        canonicalUrl: String? = nil,
        title: String = "",
        artist: String = "",
        album: String = "",
        artworkUrl: String? = nil,
        isLive: Bool,
        durationSeconds: Double = 0
    ) {
        self.mediaId = mediaId
        self.url = url
        self.canonicalUrl = canonicalUrl
        self.title = title
        self.artist = artist
        self.album = album
        self.artworkUrl = artworkUrl
        self.isLive = isLive
        self.durationSeconds = durationSeconds
    }

    /// The URL reported to JS (`streamUrl`).
    public var reportedUrl: String { canonicalUrl ?? url }

    /// Same item with new display metadata (used when JS calls `setNowPlayingInfo`).
    func withMetadata(title: String, artist: String, album: String, artworkUrl: String?, isLive: Bool, durationSeconds: Double) -> PlayableItem {
        PlayableItem(
            mediaId: mediaId,
            url: url,
            canonicalUrl: canonicalUrl,
            title: title,
            artist: artist,
            album: album,
            artworkUrl: artworkUrl,
            isLive: isLive,
            durationSeconds: durationSeconds
        )
    }

    /// Payload for the `playFromMediaId` JS event.
    var eventPayload: [String: Any] {
        var data: [String: Any] = [
            "mediaId": mediaId ?? "",
            "id": mediaId ?? "",
            "isLive": isLive,
            "streamUrl": reportedUrl,
            "title": title,
            "artist": artist
        ]
        if let artworkUrl = artworkUrl { data["imageUrl"] = artworkUrl }
        if durationSeconds > 0 { data["duration"] = durationSeconds }
        return data
    }
}
