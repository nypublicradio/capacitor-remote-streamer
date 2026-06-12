import Foundation
import AVFoundation
import Network

class RemoteStreamer: NSObject {
    private var player: AVPlayer?
    private var timeObserver: Any?
    private var playbackBufferEmptyObserver: NSKeyValueObservation?
    private var playbackBufferFullObserver: NSKeyValueObservation?
    private var playbackLikelyToKeepUpObserver: NSKeyValueObservation?
    private var playerTimeControlStatusObserver: NSKeyValueObservation?
    private var playerStatusObserver: NSKeyValueObservation?

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

    // Network monitoring
    private var pathMonitor: NWPathMonitor?
    private var lastNetworkPath: NWPath?
    
    override init() {
        super.init()
        setupInterruptionObserver()
        setupNetworkMonitor()
    }
    
    func play(url: String, completion: @escaping (Result<Void, Error>) -> Void) {
        // Cancel any pending reconnect
        cancelReconnect()
        reconnectAttempts = 0
        isReconnecting = false
        wasPlayingBeforeStall = false

        currentUrl = url
        isLiveStream = url.contains(".m3u8")

        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
        let osVersion = UIDevice.current.systemVersion
        let deviceModel = UIDevice.current.model
        let headers = ["User-Agent": "WNYC-App/iOS \(appVersion); \(deviceModel); iOS \(osVersion)"]
        guard let url = URL(string: url) else {
            completion(.failure(NSError(domain: "RemoteStreamer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])))
            return
        }
        
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let playerItem = AVPlayerItem(asset: asset)
        
        player = AVPlayer(playerItem: playerItem)
        setupAudioSession()
        setupObservers(playerItem: playerItem)
        
        player?.playImmediately(atRate: 1.0)
        completion(.success(()))
    }
    
    func pause() {
        cancelReconnect()
        player?.pause()
    }
    
    func resume() {
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
        player?.play()
    }
    
    func stop() {
        cancelReconnect()
        reconnectAttempts = 0
        isReconnecting = false
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        removeObservers()
        NotificationCenter.default.post(name: Notification.Name("RemoteStreamerStop"), object: nil)
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

    func isPlaying() -> Bool {
        return player?.timeControlStatus == .playing
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
            NotificationCenter.default.post(name: Notification.Name("RemoteStreamerPause"), object: nil)
            return
        }

        isReconnecting = true
        reconnectAttempts += 1

        // Exponential backoff: 2s, 4s, 8s
        let delay = pow(2.0, Double(reconnectAttempts))
        print("RemoteStreamer: Reconnect attempt \(reconnectAttempts)/\(maxReconnectAttempts) in \(delay)s")

        NotificationCenter.default.post(name: Notification.Name("RemoteStreamerBuffering"), object: nil)

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

        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
        let osVersion = UIDevice.current.systemVersion
        let deviceModel = UIDevice.current.model
        let headers = ["User-Agent": "WNYC-App/iOS \(appVersion); \(deviceModel); iOS \(osVersion)"]

        guard let streamUrl = URL(string: url) else { return }

        let asset = AVURLAsset(url: streamUrl, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let playerItem = AVPlayerItem(asset: asset)

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

        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
        let osVersion = UIDevice.current.systemVersion
        let deviceModel = UIDevice.current.model
        let headers = ["User-Agent": "WNYC-App/iOS \(appVersion); \(deviceModel); iOS \(osVersion)"]

        guard let streamUrl = URL(string: url) else { return }

        let asset = AVURLAsset(url: streamUrl, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let playerItem = AVPlayerItem(asset: asset)

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
            guard let self = self, let url = self.currentUrl else { return }
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
                    NotificationCenter.default.post(name: Notification.Name("RemoteStreamerPause"), object: nil)
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
        
        playbackBufferEmptyObserver = playerItem.observe(\.isPlaybackBufferEmpty, options: [.new, .initial]) { item, change in
            if change.newValue == true {
                print("Buffer is empty")
            }
        }
        
        playbackBufferFullObserver = playerItem.observe(\.isPlaybackBufferFull, options: [.new, .initial]) { item, change in
            if change.newValue == true {
                print("Buffer is full")
            }
        }
        
        playbackLikelyToKeepUpObserver = playerItem.observe(\.isPlaybackLikelyToKeepUp, options: [.new, .initial]) { item, change in
            if item.status == .readyToPlay && change.newValue == true {
                print("Playback is likely to keep up")
            }
        }

        playerTimeControlStatusObserver = player?.observe(\.timeControlStatus, options: [.new, .initial]) { [weak self] player, change in
            guard let self = self else { return }
            switch player.timeControlStatus {
            case .paused:
                if !self.isReconnecting {
                    NotificationCenter.default.post(name: Notification.Name("RemoteStreamerPause"), object: nil)
                }
            case .playing:
                // Successful playback — reset reconnect state
                self.cancelStallWatchdog()
                self.reconnectAttempts = 0
                self.isReconnecting = false
                self.wasPlayingBeforeStall = false
                NotificationCenter.default.post(name: Notification.Name("RemoteStreamerPlay"), object: nil)
            case .waitingToPlayAtSpecifiedRate:
                NotificationCenter.default.post(name: Notification.Name("RemoteStreamerBuffering"), object: nil)
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
                    NotificationCenter.default.post(name: Notification.Name("RemoteStreamerPause"), object: nil)
                } else {
                    self.isReconnecting = false
                    self.cleanupFailedPlayer()
                    NotificationCenter.default.post(name: Notification.Name("RemoteStreamerStop"), object: nil,
                        userInfo: ["error": error?.localizedDescription ?? "Playback failed"])
                }
            }
        }
        
        setupTimeObserver()
    }
    
    private func removeObservers() {
        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: player?.currentItem)
        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemFailedToPlayToEndTime, object: player?.currentItem)
        playbackBufferEmptyObserver?.invalidate()
        playbackBufferFullObserver?.invalidate()
        playbackLikelyToKeepUpObserver?.invalidate()
        playerTimeControlStatusObserver?.invalidate()
        playerStatusObserver?.invalidate()
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
        NotificationCenter.default.post(name: Notification.Name("RemoteStreamerTimeUpdate"), object: nil, userInfo: ["currentTime": time])
    }
    
    func setVolume(volume: Double) {
        player?.volume = Float(volume)
    }
    
    @objc private func playerDidFinishPlaying(note: NSNotification) {
        NotificationCenter.default.post(name: Notification.Name("RemoteStreamerEnded"), object: nil, userInfo:
            ["ended": true])
    }

    @objc private func playerFailedToPlayToEnd(note: NSNotification) {
        let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
        print("RemoteStreamer: Failed to play to end: \(error?.localizedDescription ?? "unknown")")

        if isLiveStream && reconnectAttempts < maxReconnectAttempts {
            reconnect()
        } else {
            isReconnecting = false
            cleanupFailedPlayer()
            NotificationCenter.default.post(name: Notification.Name("RemoteStreamerStop"), object: nil,
                userInfo: ["error": error?.localizedDescription ?? "Stream ended unexpectedly"])
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
            player?.pause()
        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    player?.play()
                }
            }
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
