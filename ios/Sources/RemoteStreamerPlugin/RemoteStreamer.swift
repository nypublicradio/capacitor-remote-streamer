import Foundation
import AVFoundation

class RemoteStreamer: NSObject, AVPlayerItemMetadataOutputPushDelegate {
    private var player: AVPlayer?
    private var timeObserver: Any?
    private var metadataOutput: AVPlayerItemMetadataOutput?
    private var playbackBufferEmptyObserver: NSKeyValueObservation?
    private var playbackBufferFullObserver: NSKeyValueObservation?
    private var playbackLikelyToKeepUpObserver: NSKeyValueObservation?
    private var playerTimeControlStatusObserver: NSKeyValueObservation?
    private var playerStatusObserver: NSKeyValueObservation?
    
    override init() {
        super.init()
        setupInterruptionObserver()
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
        
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let playerItem = AVPlayerItem(asset: asset)
        
        player = AVPlayer(playerItem: playerItem)
        setupAudioSession()
        setupObservers(playerItem: playerItem)
        
        player?.playImmediately(atRate: 1.0)
        completion(.success(()))
    }
    
    func pause() {
        player?.pause()
    }
    
    func resume() {
        player?.play()
    }
    
    func stop() {
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
    
    private func setupObservers(playerItem: AVPlayerItem) {
        NotificationCenter.default.addObserver(self, selector: #selector(playerDidFinishPlaying), name: .AVPlayerItemDidPlayToEndTime, object: playerItem)
        
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
                NotificationCenter.default.post(name: Notification.Name("RemoteStreamerPause"), object: nil)
            case .playing:
                NotificationCenter.default.post(name: Notification.Name("RemoteStreamerPlay"), object: nil)
            case .waitingToPlayAtSpecifiedRate:
                NotificationCenter.default.post(name: Notification.Name("RemoteStreamerBuffering"), object: nil)
                break
            @unknown default:
                break
            }
        }

        playerStatusObserver = player?.observe(\.status, options: [.new, .initial]) { player, change in
            if player.status == .failed {
                NotificationCenter.default.post(name: Notification.Name("RemoteStreamerStop"), object: nil)
            }
        }
        
        // Set up metadata output for ID3 tags
        let metadataOutput = AVPlayerItemMetadataOutput(identifiers: nil)
        metadataOutput.setDelegate(self, queue: DispatchQueue.main)
        playerItem.add(metadataOutput)
        self.metadataOutput = metadataOutput

        setupTimeObserver()
    }
    
    private func removeObservers() {
        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: player?.currentItem)
        playbackBufferEmptyObserver?.invalidate()
        playbackBufferFullObserver?.invalidate()
        playbackLikelyToKeepUpObserver?.invalidate()
        playerTimeControlStatusObserver?.invalidate()
        playerStatusObserver?.invalidate()
        removeTimeObserver()
        
        if let metadataOutput = self.metadataOutput,
           let playerItem = player?.currentItem {
            playerItem.remove(metadataOutput)
        }
        self.metadataOutput = nil
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
        //player?.setVolume({volume})
        player?.volume = Float(volume)
    }
    
    @objc private func playerDidFinishPlaying(note: NSNotification) {
        NotificationCenter.default.post(name: Notification.Name("RemoteStreamerEnded"), object: nil, userInfo:
            ["ended": true])
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
        removeObservers()
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
    }

    // AVPlayerItemMetadataOutputPushDelegate method
    func metadataOutput(_ output: AVPlayerItemMetadataOutput,
                       didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup],
                       from track: AVPlayerItemTrack?) {
        guard let group = groups.first,
              let items = group.items as? [AVMetadataItem] else {
            return
        }
        
        var metadata: [String: Any] = [:]
        
        for item in items {
            // Common metadata identifiers
            if let commonKey = item.commonKey?.rawValue, let value = item.value {
                metadata[commonKey] = value
                print("Metadata[\(commonKey)]: \(value)")
            }
            // ID3 specific keys
            else if let key = item.key as? String, let value = item.value {
                metadata[key] = value
                print("ID3[\(key)]: \(value)")
            }
            // Try to get the identifier as string
            else if let identifier = item.identifier as? String, let value = item.value {
                metadata[identifier] = value
                print("Other[\(identifier)]: \(value)")
            }
        }
        
        // Only notify if we have actual metadata
        if !metadata.isEmpty {
            NotificationCenter.default.post(
                name: Notification.Name("RemoteStreamerMetadataUpdate"),
                object: nil,
                userInfo: metadata
            )
        }
    }
}
