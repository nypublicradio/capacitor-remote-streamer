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
    
    // MARK: - Reconnection support
    private var currentURL: String?
    private var stallTimer: Timer?
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 5
    private var pathMonitor: NWPathMonitor?
    private var isReconnecting = false
    
    override init() {
        super.init()
        setupInterruptionObserver()
        setupNetworkMonitor()
    }
    
    func play(url: String, completion: @escaping (Result<Void, Error>) -> Void) {
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
        let osVersion = UIDevice.current.systemVersion
        let deviceModel = UIDevice.current.model
        let headers = ["User-Agent": "WNYC-App/iOS \(appVersion); \(deviceModel); iOS \(osVersion)"]
        guard let url = URL(string: url) else {
            completion(.failure(NSError(domain: "RemoteStreamer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])))
            return
        }
        
        // Store URL for reconnection
        currentURL = url.absoluteString
        reconnectAttempts = 0
        
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let playerItem = AVPlayerItem(asset: asset)
        
        player = AVPlayer(playerItem: playerItem)
        setupAudioSession()
        setupObservers(playerItem: playerItem)
        
        player?.playImmediately(atRate: 1.0)
        completion(.success(()))
    }
    
    func pause() {
        cancelStallTimer()
        player?.pause()
    }
    
    func resume() {
        player?.play()
    }
    
    func stop() {
        cancelStallTimer()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        removeObservers()
        currentURL = nil
        reconnectAttempts = 0
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
    
    func setVolume(volume: Double) {
        player?.volume = Float(volume)
    }
    
    // MARK: - Audio Session
    
    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("Failed to set audio session category: \(error)")
        }
    }
    
    // MARK: - Network Monitoring
    
    private func setupNetworkMonitor() {
        pathMonitor = NWPathMonitor()
        pathMonitor?.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            
            // Network became available and we're stalled - try reconnecting
            if path.status == .satisfied {
                DispatchQueue.main.async {
                    if self.player?.timeControlStatus == .waitingToPlayAtSpecifiedRate && !self.isReconnecting {
                        print("Network restored while buffering - attempting reconnect")
                        self.reconnect()
                    }
                }
            }
        }
        pathMonitor?.start(queue: DispatchQueue.global(qos: .utility))
    }
    
    // MARK: - Stall Detection & Recovery
    
    private func startStallTimer() {
        cancelStallTimer()
        
        // Exponential backoff: 5s, 10s, 20s, 40s, 60s max
        let delay = min(5.0 * pow(2.0, Double(reconnectAttempts)), 60.0)
        
        stallTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            
            if self.player?.timeControlStatus == .waitingToPlayAtSpecifiedRate {
                print("Stall timer fired after \(delay)s - attempting reconnect")
                self.reconnect()
            }
        }
    }
    
    private func cancelStallTimer() {
        stallTimer?.invalidate()
        stallTimer = nil
    }
    
    private func reconnect() {
        guard let urlString = currentURL, !isReconnecting else {
            print("Cannot reconnect: no URL stored or already reconnecting")
            return
        }
        
        guard reconnectAttempts < maxReconnectAttempts else {
            print("Max reconnect attempts (\(maxReconnectAttempts)) reached - giving up")
            stop()
            NotificationCenter.default.post(
                name: Notification.Name("RemoteStreamerError"),
                object: nil,
                userInfo: ["error": "Failed to reconnect after \(maxReconnectAttempts) attempts"]
            )
            return
        }
        
        isReconnecting = true
        reconnectAttempts += 1
        print("Reconnect attempt \(reconnectAttempts) of \(maxReconnectAttempts)")
        
        // Clean teardown
        cancelStallTimer()
        player?.pause()
        removeObservers()
        player = nil
        
        // Small delay before reconnecting
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self = self else { return }
            self.isReconnecting = false
            
            self.play(url: urlString) { result in
                switch result {
                case .success:
                    print("Reconnect successful")
                case .failure(let error):
                    print("Reconnect failed: \(error.localizedDescription)")
                }
            }
        }
    }
    
    // MARK: - Observers
    
    private func setupObservers(playerItem: AVPlayerItem) {
        // End of playback
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerDidFinishPlaying),
            name: .AVPlayerItemDidPlayToEndTime,
            object: playerItem
        )
        
        // Playback failed mid-stream
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerFailedToPlayToEnd),
            name: .AVPlayerItemFailedToPlayToEndTime,
            object: playerItem
        )
        
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
        
        playbackLikelyToKeepUpObserver = playerItem.observe(\.isPlaybackLikelyToKeepUp, options: [.new, .initial]) { [weak self] item, change in
            if item.status == .readyToPlay && change.newValue == true {
                print("Playback is likely to keep up")
                // Reset reconnect counter on successful playback
                self?.reconnectAttempts = 0
                self?.cancelStallTimer()
            }
        }

        playerTimeControlStatusObserver = player?.observe(\.timeControlStatus, options: [.new, .initial]) { [weak self] player, change in
            guard let self = self else { return }
            switch player.timeControlStatus {
            case .paused:
                self.cancelStallTimer()
                NotificationCenter.default.post(name: Notification.Name("RemoteStreamerPause"), object: nil)
            case .playing:
                self.cancelStallTimer()
                self.reconnectAttempts = 0  // Reset on successful playback
                NotificationCenter.default.post(name: Notification.Name("RemoteStreamerPlay"), object: nil)
            case .waitingToPlayAtSpecifiedRate:
                NotificationCenter.default.post(name: Notification.Name("RemoteStreamerBuffering"), object: nil)
                self.startStallTimer()  // Start watching for stall
            @unknown default:
                break
            }
        }

        playerStatusObserver = player?.observe(\.status, options: [.new, .initial]) { [weak self] player, change in
            if player.status == .failed {
                let errorMessage = player.error?.localizedDescription 
                    ?? player.currentItem?.error?.localizedDescription 
                    ?? "Unknown playback error"
                print("Player failed: \(errorMessage)")
                
                // Attempt reconnect instead of just stopping
                self?.reconnect()
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
    
    @objc private func playerDidFinishPlaying(note: NSNotification) {
        NotificationCenter.default.post(name: Notification.Name("RemoteStreamerEnded"), object: nil, userInfo: ["ended": true])
    }
    
    @objc private func playerFailedToPlayToEnd(note: NSNotification) {
        if let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error {
            print("Failed to play to end: \(error.localizedDescription)")
        }
        // Attempt reconnect
        reconnect()
    }
    
    // MARK: - Interruption Handling
    
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
    
    // MARK: - Cleanup
    
    deinit {
        cancelStallTimer()
        pathMonitor?.cancel()
        removeObservers()
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
    }
}
