import Foundation
import AVFoundation
import Network
import MediaPlayer
import UIKit

/// Player events, posted on `NotificationCenter.default`.
/// The Capacitor plugin forwards them to JS; native integrations may observe them too.
public extension Notification.Name {
    static let remoteStreamerPlay = Notification.Name("RemoteStreamerPlay")
    static let remoteStreamerPause = Notification.Name("RemoteStreamerPause")
    static let remoteStreamerStop = Notification.Name("RemoteStreamerStop")
    static let remoteStreamerEnded = Notification.Name("RemoteStreamerEnded")
    static let remoteStreamerTimeUpdate = Notification.Name("RemoteStreamerTimeUpdate")
    /// `userInfo["isBuffering"]` is true while playback waits for data, false once it plays.
    static let remoteStreamerBuffering = Notification.Name("RemoteStreamerBuffering")
    static let remoteStreamerReady = Notification.Name("RemoteStreamerReady")
    /// The item can't be played. `userInfo["message"]` describes why.
    static let remoteStreamerError = Notification.Name("RemoteStreamerError")
    /// Native code started an item with `play(item:)`. `userInfo` is the JS event payload.
    static let remoteStreamerPlayFromMediaId = Notification.Name("RemoteStreamerPlayFromMediaId")
}

public final class RemoteStreamer: NSObject {
    /// One player for the whole app, shared by the Capacitor plugin and any
    /// native integration (e.g. CarPlay).
    public static let shared = RemoteStreamer()

    /// What is currently loaded, with its display metadata.
    public private(set) var currentItem: PlayableItem?

    private let nowPlaying = NowPlayingController()

    private var player: AVPlayer?
    private var timeObserver: Any?
    private var playerTimeControlStatusObserver: NSKeyValueObservation?
    private var playerStatusObserver: NSKeyValueObservation?
    private var itemStatusObserver: NSKeyValueObservation?
    private var durationObserver: NSKeyValueObservation?

    // Reconnection state
    private var currentUrl: String?
    private var isLiveStream = false
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 3
    private var reconnectTimer: DispatchWorkItem?
    private var isReconnecting = false
    private var wasPlayingBeforeStall = false
    private var stallWatchdog: DispatchWorkItem?
    private let stallTimeoutSeconds: Double = 30.0
    private var savedPosition: Double = 0 // saved playback position for on-demand recovery
    private var hasFiredReady = false // track whether 'ready' event has been fired for current play
    /// Whether audio was playing when an interruption (e.g. a call) began.
    private var wasPlayingBeforeInterruption = false

    // Network monitoring
    private var pathMonitor: NWPathMonitor?
    private var lastNetworkPath: NWPath?

    /// Background audio only keeps the app running while something plays, so this holds
    /// it open after an item ends until the next one plays. Main thread only.
    private var endOfItemTask: UIBackgroundTaskIdentifier = .invalid

    /// `bundle:<path>` names a file the host app bundles with its web code (`public/<path>`).
    private static let bundleScheme = "bundle:"

    private override init() {
        super.init()
        setupInterruptionObserver()
        setupNetworkMonitor()
        setupRemoteCommandCenter()
    }

    /// Flag to suppress the initial .paused KVO notification when creating a new player
    private var suppressPauseNotification = false

    // MARK: - Public playback API

    /// Plays an item started from native code (for example a CarPlay or Android Auto
    /// integration). Shows its metadata in Now Playing, sets up the lock-screen
    /// controls and tells JS via the `playFromMediaId` event.
    public func play(item: PlayableItem) {
        currentItem = item
        enableCommandCenter(seekEnabled: !item.isLive)
        nowPlaying.begin(item, defaultTitle: PlayerSettings.shared.defaultTitle)
        startPlayback(url: item.url, isLive: item.isLive) { result in
            if case .failure(let error) = result {
                print("RemoteStreamer: play(item:) failed: \(error.localizedDescription)")
            }
        }
        post(.remoteStreamerPlayFromMediaId, userInfo: item.eventPayload)
    }

    /// Plays a URL requested by JS. JS sends display metadata separately via `setNowPlayingInfo`.
    /// A `bundle:<path>` URL plays a file the host app bundles with its web code.
    func play(url: String, completion: @escaping (Result<Void, Error>) -> Void) {
        guard let playableUrl = Self.playableUrl(url) else {
            completion(.failure(NSError(domain: "RemoteStreamer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Nothing to play at \(url)"])))
            return
        }
        let isLive = playableUrl.contains(".m3u8")
        currentItem = PlayableItem(url: playableUrl, isLive: isLive)
        startPlayback(url: playableUrl, isLive: isLive, completion: completion)
    }

    /// `url`, or for `bundle:<path>` the bundled file's URL; nil when that file doesn't exist.
    static func playableUrl(_ url: String) -> String? {
        guard url.hasPrefix(bundleScheme) else { return url }
        let path = url.dropFirst(bundleScheme.count).drop(while: { $0 == "/" })
        let file = Bundle.main.bundleURL.appendingPathComponent("public").appendingPathComponent(String(path))
        return FileManager.default.fileExists(atPath: file.path) ? file.absoluteString : nil
    }

    /// Updates the display metadata of the current item (JS `setNowPlayingInfo`).
    func setNowPlayingMetadata(title: String, artist: String, album: String, durationSeconds: Double, artworkUrl: String?, isLive: Bool) {
        let base = currentItem ?? PlayableItem(url: currentUrl ?? "", isLive: isLive)
        let item = base.withMetadata(title: title, artist: artist, album: album, artworkUrl: artworkUrl, isLive: isLive, durationSeconds: durationSeconds)
        currentItem = item
        nowPlaying.update(item, defaultTitle: PlayerSettings.shared.defaultTitle)
    }

    private func startPlayback(url: String, isLive: Bool, completion: @escaping (Result<Void, Error>) -> Void) {
        // Cancel any pending reconnect
        cancelReconnect()
        reconnectAttempts = 0
        isReconnecting = false
        wasPlayingBeforeStall = false
        savedPosition = 0

        // Remove old observers BEFORE replacing the player to prevent stale notifications
        removeObservers()
        suppressPauseNotification = true

        currentUrl = url
        isLiveStream = isLive
        hasFiredReady = false

        guard let streamUrl = URL(string: url) else {
            completion(.failure(NSError(domain: "RemoteStreamer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])))
            return
        }

        let playerItem = makePlayerItem(url: streamUrl)
        player = AVPlayer(playerItem: playerItem)
        setupAudioSession()
        setupObservers(playerItem: playerItem)

        player?.playImmediately(atRate: 1.0)
        completion(.success(()))
    }

    /// Builds a player item that sends the host-configured User-Agent.
    private func makePlayerItem(url: URL) -> AVPlayerItem {
        let headers = ["User-Agent": PlayerSettings.shared.userAgent]
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        return AVPlayerItem(asset: asset)
    }

    public func pause() {
        cancelReconnect()
        // The user paused: don't resume when the network comes back
        wasPlayingBeforeStall = false
        player?.pause()
    }

    public func resume() {
        // Nothing loaded (stopped, or released between items): play the last item again
        if player?.currentItem == nil, let item = currentItem {
            restart(item)
            return
        }
        // For live streams: if player is failed, stalled, or nil, rebuild from scratch
        if isLiveStream, let url = currentUrl {
            let needsRebuild = (player == nil)
                || (player?.status == .failed)
                || (player?.currentItem?.status == .failed)
                || (player?.timeControlStatus == .waitingToPlayAtSpecifiedRate)
            if needsRebuild {
                print("RemoteStreamer: resume() on stalled/failed live stream, rebuilding")
                cancelReconnect()
                reconnectAttempts = 0
                isReconnecting = true
                performReconnect(url: url)
                return
            }
        }
        // For on-demand: if player is nil, failed, or stalled, rebuild and seek to saved position
        if !isLiveStream, let url = currentUrl {
            let needsRebuild = (player == nil)
                || (player?.status == .failed)
                || (player?.currentItem?.status == .failed)
                || (player?.timeControlStatus == .waitingToPlayAtSpecifiedRate && wasPlayingBeforeStall)
            if needsRebuild {
                print("RemoteStreamer: resume() on stalled/failed on-demand, rebuilding at position \(savedPosition)s")
                performOnDemandResume()
                return
            }
        }
        // An on-demand item that played to its end starts over
        if !isLiveStream, Self.reachedEnd(player?.currentItem) {
            player?.seek(to: .zero)
        }
        player?.play()
    }

    /// Plays `item` again from the start, with its Now Playing info (a stop clears that).
    private func restart(_ item: PlayableItem) {
        enableCommandCenter(seekEnabled: !item.isLive)
        nowPlaying.begin(item, defaultTitle: PlayerSettings.shared.defaultTitle)
        startPlayback(url: item.url, isLive: item.isLive) { result in
            if case .failure(let error) = result {
                print("RemoteStreamer: restart failed: \(error.localizedDescription)")
            }
        }
    }

    public func stop() {
        cancelReconnect()
        reconnectAttempts = 0
        isReconnecting = false
        wasPlayingBeforeStall = false
        savedPosition = 0
        // Observers first, so that stopping isn't also reported as a pause
        removeObservers()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        post(.remoteStreamerStop)
    }

    /// Whether the item played to its end (reported as `ended`, not as a pause).
    private static func reachedEnd(_ item: AVPlayerItem?) -> Bool {
        guard let item = item, item.duration.isNumeric else { return false }
        return CMTimeCompare(item.currentTime(), item.duration) >= 0
    }

    func seekTo(position: Double) {
        let time = CMTime(seconds: position, preferredTimescale: 1000)
        player?.seek(to: time)
    }

    func seekBy(offset: Double) {
        guard let currentTime = player?.currentTime() else { return }
        let newTime = CMTime(seconds: currentTime.seconds + offset, preferredTimescale: 1000)
        player?.seek(to: newTime)
    }

    public func isPlaying() -> Bool {
        return player?.timeControlStatus == .playing
    }

    func getCurrentState() -> [String: Any] {
        let currentTime = player?.currentTime().seconds ?? 0
        let playerDuration = player?.currentItem?.duration.seconds ?? 0
        var duration = (playerDuration.isFinite && playerDuration > 0) ? playerDuration : 0
        var state: [String: Any] = [
            "isPlaying": isPlaying(),
            "currentUrl": currentUrl as Any,
            "currentTime": currentTime.isFinite ? currentTime : 0,
            "isLiveStream": isLiveStream,
            "currentMediaId": currentItem?.mediaId as Any
        ]
        // Metadata of the current item, so the app can rebuild its UI when it
        // opens after playback was started natively (e.g. from the car).
        if let item = currentItem {
            if duration == 0 && item.durationSeconds > 0 {
                duration = item.durationSeconds
            }
            if !item.title.isEmpty { state["title"] = item.title }
            if !item.artist.isEmpty { state["artist"] = item.artist }
            if let artworkUrl = item.artworkUrl { state["imageUrl"] = artworkUrl }
            state["streamUrl"] = item.reportedUrl
        }
        state["duration"] = duration
        return state
    }

    // MARK: - Events

    /// Posts a player event and keeps Now Playing in step with it, so the lock
    /// screen and CarPlay stay correct even when no Capacitor plugin is listening.
    private func post(_ name: Notification.Name, userInfo: [AnyHashable: Any]? = nil) {
        switch name {
        case .remoteStreamerPlay:
            nowPlaying.setPlaybackState(.playing)
            // Playing again: background audio keeps the app running from here
            endEndOfItemTask()
        case .remoteStreamerPause:
            nowPlaying.setPlaybackState(.paused)
        case .remoteStreamerStop:
            // Not the end of the end-of-item task: JS releases the player before starting
            // the next item, which must still be able to run
            nowPlaying.setPlaybackState(.stopped)
        default:
            break
        }
        NotificationCenter.default.post(name: name, object: nil, userInfo: userInfo)
    }

    // MARK: - Remote Command Center (single owner)

    /// Enable or disable seek controls based on whether the content is live or on-demand.
    func setSeekEnabled(_ enabled: Bool) {
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.skipForwardCommand.isEnabled = enabled
        commandCenter.skipBackwardCommand.isEnabled = enabled
        commandCenter.changePlaybackPositionCommand.isEnabled = enabled
    }

    /// Enable all playback controls (called when playback starts)
    func enableCommandCenter(seekEnabled: Bool = false) {
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.playCommand.isEnabled = true
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.isEnabled = true
        setSeekEnabled(seekEnabled)
    }

    /// Disable all playback controls (called when nothing is playing)
    func disableCommandCenter() {
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.playCommand.isEnabled = false
        commandCenter.pauseCommand.isEnabled = false
        commandCenter.togglePlayPauseCommand.isEnabled = false
        commandCenter.skipForwardCommand.isEnabled = false
        commandCenter.skipBackwardCommand.isEnabled = false
        commandCenter.changePlaybackPositionCommand.isEnabled = false
    }

    private func setupRemoteCommandCenter() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.resume()
            self?.nowPlaying.setPlaybackState(.playing)
            return .success
        }

        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            self?.nowPlaying.setPlaybackState(.paused)
            return .success
        }

        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            if self.isPlaying() {
                self.pause()
                self.nowPlaying.setPlaybackState(.paused)
            } else {
                self.resume()
                self.nowPlaying.setPlaybackState(.playing)
            }
            return .success
        }

        commandCenter.skipForwardCommand.preferredIntervals = [10]
        commandCenter.skipForwardCommand.addTarget { [weak self] _ in
            self?.seekBy(offset: 10)
            return .success
        }

        commandCenter.skipBackwardCommand.preferredIntervals = [10]
        commandCenter.skipBackwardCommand.addTarget { [weak self] _ in
            self?.seekBy(offset: -10)
            return .success
        }

        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seekTo(position: event.positionTime)
            self?.nowPlaying.updateElapsed(event.positionTime, duration: nil)
            return .success
        }

        // Start with all controls disabled until playback actually begins
        disableCommandCenter()
    }

    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("Failed to set audio session category: \(error)")
        }
    }

    // MARK: - Network Monitor

    private func setupNetworkMonitor() {
        pathMonitor = NWPathMonitor()
        pathMonitor?.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let hadNetwork = self.lastNetworkPath?.status == .satisfied
            self.lastNetworkPath = path

            // Network just came back — if we're stalled or failed on a live stream, reconnect
            if path.status == .satisfied && hadNetwork == false {
                DispatchQueue.main.async {
                    self.handleNetworkRestored()
                }
            }
        }
        pathMonitor?.start(queue: DispatchQueue.global(qos: .utility))
    }

    private func handleNetworkRestored() {
        guard let url = currentUrl, wasPlayingBeforeStall else { return }

        if isLiveStream {
            print("RemoteStreamer: Network restored, rebuilding live stream player")
            cancelReconnect()
            reconnectAttempts = 0
            isReconnecting = true
            performReconnect(url: url)
        } else {
            // On-demand: rebuild and seek to saved position
            print("RemoteStreamer: Network restored, resuming on-demand at position \(savedPosition)s")
            performOnDemandResume()
        }
    }

    // MARK: - Reconnection

    private func reconnect() {
        guard let url = currentUrl, isLiveStream else { return }
        guard reconnectAttempts < maxReconnectAttempts else {
            print("RemoteStreamer: Max reconnect attempts (\(maxReconnectAttempts)) reached, giving up")
            isReconnecting = false
            wasPlayingBeforeStall = true  // so handleNetworkRestored will retry when network returns
            cleanupFailedPlayer()
            post(.remoteStreamerPause)
            return
        }

        isReconnecting = true
        reconnectAttempts += 1

        // Exponential backoff: 2s, 4s, 8s
        let delay = pow(2.0, Double(reconnectAttempts))
        print("RemoteStreamer: Reconnect attempt \(reconnectAttempts)/\(maxReconnectAttempts) in \(delay)s")

        post(.remoteStreamerBuffering, userInfo: ["isBuffering": true])

        let workItem = DispatchWorkItem { [weak self] in
            guard let self = self, self.isReconnecting else { return }
            self.performReconnect(url: url)
        }
        reconnectTimer = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
    }

    private func performReconnect(url: String) {
        // Tear down the old player completely
        cleanupFailedPlayer()

        guard let streamUrl = URL(string: url) else { return }

        let playerItem = makePlayerItem(url: streamUrl)
        player = AVPlayer(playerItem: playerItem)
        setupAudioSession()
        setupObservers(playerItem: playerItem)
        player?.playImmediately(atRate: 1.0)
    }

    private func performOnDemandResume() {
        guard let url = currentUrl else { return }
        print("RemoteStreamer: performOnDemandResume: rebuilding at position \(savedPosition)s")
        cancelReconnect()
        cancelStallWatchdog()

        // Tear down the old player completely
        cleanupFailedPlayer()

        guard let streamUrl = URL(string: url) else { return }

        let playerItem = makePlayerItem(url: streamUrl)
        player = AVPlayer(playerItem: playerItem)
        setupAudioSession()
        setupObservers(playerItem: playerItem)

        // Seek to saved position before playing
        if savedPosition > 0 {
            let seekTime = CMTime(seconds: savedPosition, preferredTimescale: 1000)
            player?.seek(to: seekTime) { [weak self] _ in
                self?.player?.playImmediately(atRate: 1.0)
            }
        } else {
            player?.playImmediately(atRate: 1.0)
        }

        wasPlayingBeforeStall = false
        isReconnecting = false
    }

    private func cancelReconnect() {
        reconnectTimer?.cancel()
        reconnectTimer = nil
        cancelStallWatchdog()
    }

    private func startStallWatchdog() {
        cancelStallWatchdog()
        // Save position for on-demand recovery
        if !isLiveStream, let currentTime = player?.currentTime() {
            let pos = currentTime.seconds
            if pos > 0 && pos.isFinite { savedPosition = pos }
        }
        let workItem = DispatchWorkItem { [weak self] in
            guard let self = self, self.currentUrl != nil else { return }
            // If still waiting/stalled after timeout
            if self.player?.timeControlStatus == .waitingToPlayAtSpecifiedRate ||
               self.player?.status == .failed {
                if self.isLiveStream {
                    // If already reconnecting, don't start another cycle — let it finish
                    guard !self.isReconnecting else {
                        print("RemoteStreamer: Stall watchdog fired but already reconnecting, skipping")
                        return
                    }
                    print("RemoteStreamer: Stall watchdog fired after \(self.stallTimeoutSeconds)s, attempting reconnect")
                    self.wasPlayingBeforeStall = true
                    self.reconnect()
                } else {
                    // On-demand: just mark as paused/stalled, wait for network
                    print("RemoteStreamer: Stall watchdog fired for on-demand, pausing at position \(self.savedPosition)s")
                    self.post(.remoteStreamerPause)
                }
            }
        }
        stallWatchdog = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + stallTimeoutSeconds, execute: workItem)
    }

    private func cancelStallWatchdog() {
        stallWatchdog?.cancel()
        stallWatchdog = nil
    }

    private func cleanupFailedPlayer() {
        removeObservers()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
    }

    // MARK: - Observers

    private func setupObservers(playerItem: AVPlayerItem) {
        NotificationCenter.default.addObserver(self, selector: #selector(playerDidFinishPlaying), name: .AVPlayerItemDidPlayToEndTime, object: playerItem)
        NotificationCenter.default.addObserver(self, selector: #selector(playerFailedToPlayToEnd), name: .AVPlayerItemFailedToPlayToEndTime, object: playerItem)

        // Loading failures (a bad URL, a server error, no network) are reported on the item
        itemStatusObserver = playerItem.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            DispatchQueue.main.async {
                // A late report for an item that has since been replaced isn't a failure now
                guard let self = self, item === self.player?.currentItem else { return }
                self.itemFailed(item.error)
            }
        }

        durationObserver = playerItem.observe(\.duration, options: [.new]) { [weak self] item, _ in
            guard let self = self else { return }
            let duration = item.duration.seconds
            if duration.isFinite && duration > 0 && !self.hasFiredReady {
                self.hasFiredReady = true
                let currentTime = self.player?.currentTime().seconds ?? 0
                DispatchQueue.main.async {
                    self.post(.remoteStreamerReady, userInfo: [
                        "duration": duration,
                        "currentTime": currentTime.isFinite ? currentTime : 0,
                        "isLiveStream": self.isLiveStream
                    ])
                }
            }
        }

        playerTimeControlStatusObserver = player?.observe(\.timeControlStatus, options: [.new]) { [weak self] player, change in
            guard let self = self else { return }
            switch player.timeControlStatus {
            case .paused:
                if self.suppressPauseNotification {
                    self.suppressPauseNotification = false
                } else if !self.isReconnecting && !Self.reachedEnd(player.currentItem) {
                    DispatchQueue.main.async {
                        self.post(.remoteStreamerPause)
                    }
                }
            case .playing:
                // Successful playback — reset reconnect state
                self.suppressPauseNotification = false
                self.cancelStallWatchdog()
                self.reconnectAttempts = 0
                self.isReconnecting = false
                self.wasPlayingBeforeStall = false

                // For live streams, AVPlayerItem.duration is indefinite (NaN) so the
                // durationObserver will never fire.  Emit the 'ready' event here once
                // playback actually starts so the JS layer exits its loading state.
                // For on-demand content, wait for the durationObserver to fire with the
                // real duration — don't emit "ready" with duration 0 prematurely.
                if !self.hasFiredReady && self.isLiveStream {
                    self.hasFiredReady = true
                    let currentTime = self.player?.currentTime().seconds ?? 0
                    DispatchQueue.main.async {
                        self.post(.remoteStreamerReady, userInfo: [
                            "duration": 0.0,
                            "currentTime": currentTime.isFinite ? currentTime : 0,
                            "isLiveStream": self.isLiveStream
                        ])
                    }
                }

                DispatchQueue.main.async {
                    self.post(.remoteStreamerBuffering, userInfo: ["isBuffering": false])
                    self.post(.remoteStreamerPlay)
                }
            case .waitingToPlayAtSpecifiedRate:
                DispatchQueue.main.async {
                    self.post(.remoteStreamerBuffering, userInfo: ["isBuffering": true])
                }
                // Save position and start stall watchdog (but not if already reconnecting)
                self.wasPlayingBeforeStall = true
                if !self.isLiveStream, let currentTime = self.player?.currentTime() {
                    let pos = currentTime.seconds
                    if pos > 0 && pos.isFinite { self.savedPosition = pos }
                }
                if !self.isReconnecting {
                    self.startStallWatchdog()
                }
            @unknown default:
                break
            }
        }

        playerStatusObserver = player?.observe(\.status, options: [.new, .initial]) { [weak self] player, change in
            guard let self = self else { return }
            if player.status == .failed {
                let error = player.error ?? player.currentItem?.error
                print("RemoteStreamer: Player failed: \(error?.localizedDescription ?? "unknown")")

                if self.isLiveStream && self.reconnectAttempts < self.maxReconnectAttempts {
                    self.reconnect()
                } else if !self.isLiveStream {
                    // On-demand: save position and wait for network/user action
                    if let currentTime = self.player?.currentTime() {
                        let pos = currentTime.seconds
                        if pos > 0 && pos.isFinite { self.savedPosition = pos }
                    }
                    self.wasPlayingBeforeStall = true
                    self.isReconnecting = false
                    print("RemoteStreamer: On-demand error: saved position=\(self.savedPosition)s, waiting for network")
                    self.post(.remoteStreamerPause)
                } else {
                    self.isReconnecting = false
                    self.cleanupFailedPlayer()
                    let message = error?.localizedDescription ?? "Playback failed"
                    self.post(.remoteStreamerStop, userInfo: ["error": message])
                    self.post(.remoteStreamerError, userInfo: ["message": message])
                }
            }
        }

        setupTimeObserver()
    }

    /// The current item can't be played. Offline, it waits for the network to come back
    /// (`handleNetworkRestored`); a live stream retries; anything else is an error. Main thread.
    private func itemFailed(_ error: Error?) {
        print("RemoteStreamer: Item failed: \(error?.localizedDescription ?? "unknown")")
        if Self.isOffline(error) {
            if !isLiveStream, let currentTime = player?.currentTime() {
                let pos = currentTime.seconds
                if pos > 0 && pos.isFinite { savedPosition = pos }
            }
            wasPlayingBeforeStall = true
            post(.remoteStreamerBuffering, userInfo: ["isBuffering": true])
        } else if isLiveStream && reconnectAttempts < maxReconnectAttempts {
            reconnect()
        } else {
            cancelReconnect()
            isReconnecting = false
            post(.remoteStreamerError, userInfo: ["message": error?.localizedDescription ?? "Playback failed"])
        }
    }

    /// Whether `error` means there's no network (AVFoundation wraps the URL error).
    private static func isOffline(_ error: Error?) -> Bool {
        guard let error = error.map({ $0 as NSError }) else { return false }
        let offlineCodes = [NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorTimedOut]
        if error.domain == NSURLErrorDomain && offlineCodes.contains(error.code) {
            return true
        }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError {
            return underlying.domain == NSURLErrorDomain && offlineCodes.contains(underlying.code)
        }
        return false
    }

    private func removeObservers() {
        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: player?.currentItem)
        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemFailedToPlayToEndTime, object: player?.currentItem)
        playerTimeControlStatusObserver?.invalidate()
        playerStatusObserver?.invalidate()
        itemStatusObserver?.invalidate()
        durationObserver?.invalidate()
        removeTimeObserver()
    }

    private func setupTimeObserver() {
        let interval = CMTime(seconds: 1, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserver = player?.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            self?.notifyTimeUpdate(time: time.seconds)
        }
    }

    private func removeTimeObserver() {
        if let observer = timeObserver {
            player?.removeTimeObserver(observer)
            timeObserver = nil
        }
    }

    private func notifyTimeUpdate(time: Double) {
        var userInfo: [String: Any] = ["currentTime": time]
        var duration: Double?
        // Include duration so the Now Playing timeline can be set
        if let itemDuration = player?.currentItem?.duration, itemDuration.isNumeric {
            userInfo["duration"] = itemDuration.seconds
            duration = itemDuration.seconds
        }
        post(.remoteStreamerTimeUpdate, userInfo: userInfo)

        // Update Now Playing directly so the timeline works even when the
        // Capacitor plugin/WebView isn't loaded (e.g. CarPlay-only playback)
        nowPlaying.updateElapsed(time, duration: duration)
    }

    func setVolume(volume: Double) {
        player?.volume = Float(volume)
    }

    @objc private func playerDidFinishPlaying(note: NSNotification) {
        // May arrive on any thread
        DispatchQueue.main.async {
            // A late notification for an item that has since been replaced isn't an end
            guard let item = note.object as? AVPlayerItem, item === self.player?.currentItem else { return }
            // Nothing plays now, so iOS could suspend the app (and its web view) before
            // JS starts the next item: keep it running until something plays again
            self.beginEndOfItemTask()
            self.post(.remoteStreamerEnded, userInfo: ["ended": true])
        }
    }

    /// Keeps the app running for a while (iOS allows about 30 s) after an item ends.
    private func beginEndOfItemTask() {
        endEndOfItemTask()
        endOfItemTask = UIApplication.shared.beginBackgroundTask(withName: "RemoteStreamerNextItem") { [weak self] in
            self?.endEndOfItemTask()
        }
    }

    private func endEndOfItemTask() {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { self.endEndOfItemTask() }
            return
        }
        guard endOfItemTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(endOfItemTask)
        endOfItemTask = .invalid
    }

    @objc private func playerFailedToPlayToEnd(note: NSNotification) {
        let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
        print("RemoteStreamer: Failed to play to end: \(error?.localizedDescription ?? "unknown")")

        if isLiveStream && reconnectAttempts < maxReconnectAttempts {
            reconnect()
        } else {
            isReconnecting = false
            cleanupFailedPlayer()
            let message = error?.localizedDescription ?? "Stream ended unexpectedly"
            post(.remoteStreamerStop, userInfo: ["error": message])
            // Offline, playback resumes when the network comes back (handleNetworkRestored)
            if !Self.isOffline(error) {
                post(.remoteStreamerError, userInfo: ["message": message])
            }
        }
    }

    private func setupInterruptionObserver() {
        NotificationCenter.default.addObserver(self, selector: #selector(handleInterruption), name: AVAudioSession.interruptionNotification, object: nil)
    }

    @objc private func handleInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            wasPlayingBeforeInterruption = isPlaying()
            player?.pause()
        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                // Only what the interruption paused: not audio the user had paused
                if options.contains(.shouldResume) && wasPlayingBeforeInterruption {
                    player?.play()
                }
            }
            wasPlayingBeforeInterruption = false
        @unknown default:
            break
        }
    }

    deinit {
        cancelReconnect()
        pathMonitor?.cancel()
        removeObservers()
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
    }
}
