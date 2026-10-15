import Foundation
import MediaPlayer
import UIKit

/// The single owner of `MPNowPlayingInfoCenter`: the lock screen, Control Center
/// and CarPlay's Now Playing screen all read from it.
///
/// Lives inside the player core (not the Capacitor plugin) so it stays correct
/// when playback runs without the WebView. All writes happen on the main thread.
final class NowPlayingController {
    /// Changes whenever new metadata is shown, so late-arriving artwork for an
    /// earlier item is discarded.
    private var artworkRequestId = UUID()

    enum PlaybackState {
        case playing, paused, stopped
    }

    /// Replaces everything for an item started natively (e.g. from CarPlay).
    func begin(_ item: PlayableItem, defaultTitle: String) {
        onMain {
            var info: [String: Any] = [:]
            self.apply(item, defaultTitle: defaultTitle, to: &info)
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = 0.0
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            MPNowPlayingInfoCenter.default().playbackState = .playing
            self.loadArtwork(for: item)
        }
    }

    /// Merges new metadata into what's shown (JS `setNowPlayingInfo`).
    func update(_ item: PlayableItem, defaultTitle: String) {
        onMain {
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            self.apply(item, defaultTitle: defaultTitle, to: &info)
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            self.loadArtwork(for: item)
        }
    }

    func setPlaybackState(_ state: PlaybackState) {
        onMain {
            let center = MPNowPlayingInfoCenter.default()
            switch state {
            case .playing:
                center.playbackState = .playing
                self.setRate(1.0)
            case .paused:
                center.playbackState = .paused
                self.setRate(0.0)
            case .stopped:
                center.playbackState = .stopped
                center.nowPlayingInfo = nil
                self.artworkRequestId = UUID()
            }
        }
    }

    /// Keeps the scrubber/timeline in sync with the player.
    func updateElapsed(_ time: Double, duration: Double?) {
        onMain {
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = time
            if let duration = duration, duration.isFinite, duration > 0 {
                info[MPMediaItemPropertyPlaybackDuration] = duration
            }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }

    // MARK: - Private

    private func apply(_ item: PlayableItem, defaultTitle: String, to info: inout [String: Any]) {
        info[MPMediaItemPropertyTitle] = item.title.isEmpty ? defaultTitle : item.title
        info[MPMediaItemPropertyArtist] = item.artist
        info[MPMediaItemPropertyAlbumTitle] = item.album
        info[MPNowPlayingInfoPropertyIsLiveStream] = item.isLive
        info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
        // Never set duration to 0: that disables the interactive scrubber.
        // When unknown, leave it out and let the time observer fill it in.
        if item.isLive {
            info.removeValue(forKey: MPMediaItemPropertyPlaybackDuration)
        } else if item.durationSeconds > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = item.durationSeconds
        }
    }

    private func setRate(_ rate: Double) {
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPNowPlayingInfoPropertyPlaybackRate] = rate
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func loadArtwork(for item: PlayableItem) {
        let requestId = UUID()
        artworkRequestId = requestId
        guard let urlString = item.artworkUrl, let url = URL(string: urlString) else { return }

        DispatchQueue.global(qos: .userInitiated).async {
            guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { return }
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            DispatchQueue.main.async {
                guard self.artworkRequestId == requestId else { return }
                var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                info[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }
    }

    private func onMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.async(execute: work)
        }
    }
}
