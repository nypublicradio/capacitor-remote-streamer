import Foundation
import AVFoundation

class RemoteStreamer: NSObject {
    private var player: AVPlayer?
    private var timeObserver: Any?
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
        guard let url = URL(string: url) else {
            completion(.failure(NSError(domain: "RemoteStreamer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])))
            return
        }
        
        let asset = AVURLAsset(url: url)
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
    
    private func setVolume(volume: Double) {
        NotificationCenter.default.post(name: Notification.Name("RemoteStreamerSetVolume"), object: nil, userInfo: ["volume": volume])
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
}
