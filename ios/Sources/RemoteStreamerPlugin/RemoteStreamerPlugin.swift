import Foundation
import Capacitor
import MediaPlayer
import CarPlay

@objc(RemoteStreamerPlugin)
public class RemoteStreamerPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "RemoteStreamerPlugin"
    public let jsName = "RemoteStreamer"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "play", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pause", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resume", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "seekTo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setNowPlayingInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setVolume", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "releasePlayer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setMediaItems", returnType: CAPPluginReturnPromise)
    ]
    
    private let implementation = RemoteStreamer()

    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(handlePlayEvent), name: Notification.Name("RemoteStreamerPlay"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handlePauseEvent), name: Notification.Name("RemoteStreamerPause"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleStopEvent), name: Notification.Name("RemoteStreamerStop"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleEndedEvent), name: Notification.Name("RemoteStreamerEnded"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleTimeUpdateEvent), name: Notification.Name("RemoteStreamerTimeUpdate"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleBufferingEvent), name: Notification.Name("RemoteStreamerBuffering"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleCarPlayPlayRequest), name: Notification.Name("CarPlayPlayRequest"), object: nil)
        setupRemoteTransportControls()

        // Initialize CarPlayMediaManager early so it can receive CarPlay connection notifications
        if #available(iOS 14.0, *) {
            _ = CarPlayMediaManager.shared
        }
    }

    @objc func handlePlayEvent() {
        print("[RemoteStreamerPlugin] handlePlayEvent fired")
        notifyListeners("play", data: nil)
        // Update playback rate so Now Playing shows correct play/pause state
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        print("[RemoteStreamerPlugin] nowPlayingInfo after play: title=\(info[MPMediaItemPropertyTitle] ?? "nil"), rate=1.0")
    }

    @objc func handlePauseEvent() {
        print("[RemoteStreamerPlugin] handlePauseEvent fired")
        notifyListeners("pause", data: nil)
        // Update playback rate so CarPlay/Now Playing shows correct play/pause state
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPNowPlayingInfoPropertyPlaybackRate] = 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        print("[RemoteStreamerPlugin] nowPlayingInfo after pause: title=\(info[MPMediaItemPropertyTitle] ?? "nil"), rate=0.0")
    }

    @objc func handleStopEvent() {
            notifyListeners("stop", data: nil)
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    @objc func handleEndedEvent() {
            notifyListeners("ended", data: ["ended": true])
    }

    @objc func handleBufferingEvent() {
        notifyListeners("buffering", data: nil)
    }

    @objc func handleTimeUpdateEvent(notification: Notification) {
        if let userInfo = notification.userInfo, let currentTime = userInfo["currentTime"] as? Double {
            notifyListeners("timeUpdate", data: ["currentTime": currentTime])
            // Update elapsed time and duration for the Now Playing timeline
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
            // Update duration from the player if available
            if let duration = userInfo["duration"] as? Double, duration > 0 {
                info[MPMediaItemPropertyPlaybackDuration] = duration
            }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }

    @objc func setMediaItems(_ call: CAPPluginCall) {
        guard let items = call.getArray("items", [String: Any].self) else {
            call.reject("Must provide items array")
            return
        }

        if #available(iOS 14.0, *) {
            CarPlayMediaManager.shared.setMediaItems(items)
        }
        call.resolve()
    }

    @objc func handleCarPlayPlayRequest(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let streamUrl = userInfo["streamUrl"] as? String else { return }

        let isLive = userInfo["isLive"] as? Bool ?? streamUrl.contains(".m3u8")

        // Enable command center controls for CarPlay-initiated playback
        enableRemoteTransportControls(enableSeek: !isLive)

        // Start playback — this activates the audio session
        implementation.play(url: streamUrl) { _ in }

        // Set Now Playing info AFTER play() so the audio session is active
        let mediaId = userInfo["id"] as? String ?? ""
        if #available(iOS 14.0, *) {
            if let metadata = CarPlayMediaManager.shared.getMetadata(for: mediaId) {
                var nowPlayingInfo = [String: Any]()
                nowPlayingInfo[MPMediaItemPropertyTitle] = metadata.title
                nowPlayingInfo[MPMediaItemPropertyArtist] = metadata.subtitle
                nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = metadata.isLive
                nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
                nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = 0.0
                if !metadata.isLive && metadata.durationSeconds > 0 {
                    nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = Double(metadata.durationSeconds)
                    nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
                }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo

                // Load artwork asynchronously
                if !metadata.imageUrl.isEmpty, let url = URL(string: metadata.imageUrl) {
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
        }

        // Notify JS about CarPlay-initiated playback
        notifyListeners("playFromCarPlay", data: ["id": mediaId, "isLive": isLive])

        // Navigate to Now Playing screen (nowPlayingInfo is already set above)
        if #available(iOS 14.0, *) {
            if let controller = CarPlayMediaManager.shared.interfaceController {
                let nowPlayingTemplate = CPNowPlayingTemplate.shared
                if !(controller.topTemplate is CPNowPlayingTemplate) {
                    controller.pushTemplate(nowPlayingTemplate, animated: true, completion: nil)
                }
            }
        }
    }

    @objc func play(_ call: CAPPluginCall) {
        guard let url = call.getString("url") else {
            call.reject("Must provide a URL")
            return
        }

        if (call.getBool("enableCommandCenter", false)) {
            if (call.getBool("enableCommandCenterSeek", false)) {
                enableRemoteTransportControls(enableSeek: true)
            } else {
                enableRemoteTransportControls()
            }
        } else {
            disableRemoteTransportControls()
        }
        
        implementation.play(url: url) { result in
            print("play")
            switch result {
            case .success:
                call.resolve()
            case .failure(let error):
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func setNowPlayingInfo(_ call: CAPPluginCall) {
        guard let url = call.getString("imageUrl") else {
            call.reject("Must provide a URL")
            return
        }

        updateNowPlayingInfo(title: call.getString("title") ?? "", artist: call.getString("artist") ?? "",
            album: call.getString("album") ?? "", duration: call.getString("duration") ?? "",imageURL: URL(string: url),isLiveStream: call.getBool("isLiveStream", false))
        call.resolve()
    }
    
    @objc func pause(_ call: CAPPluginCall) {
        print("pause")
        implementation.pause()
        call.resolve()
    }
    
    @objc func resume(_ call: CAPPluginCall) {
        print("resume")
        implementation.resume()
        call.resolve()
    }
    
    @objc func stop(_ call: CAPPluginCall) {
        print("stop")
        implementation.stop()
        call.resolve()
    }
    
    @objc func releasePlayer(_ call: CAPPluginCall) {
        print("releasePlayer")
        implementation.stop()
        call.resolve()
    }
    
    @objc func setVolume(_ call: CAPPluginCall) {
        print("set volume")
        implementation.setVolume(volume: call.getDouble("volume")!)
        call.resolve()
    }
    
    @objc func seekTo(_ call: CAPPluginCall) {
        guard let position = call.getDouble("position") else {
            call.reject("Must provide a position")
            return
        }
        
        implementation.seekTo(position: position)
        call.resolve()
    }

    func updateNowPlayingInfo(title: String, artist: String, album: String, duration: String, imageURL: URL?, isLiveStream: Bool) {
        var nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [String: Any]()
        nowPlayingInfo[MPMediaItemPropertyTitle] = title
        nowPlayingInfo[MPMediaItemPropertyArtist] = artist
        nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album
        nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = isLiveStream
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = 1.0

        // Set duration as a Double (required for the timeline to appear)
        // NEVER set duration to 0 — that disables the interactive scrubber
        if let durationValue = Double(duration), durationValue > 0 {
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = durationValue
        }
        // If duration is unknown, omit it — handleTimeUpdateEvent will fill it from the player

        // Set now playing info immediately (without artwork) so controls appear
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo

        // Load artwork asynchronously and update
        if let imageURL = imageURL {
            DispatchQueue.global(qos: .userInitiated).async {
                if let imageData = try? Data(contentsOf: imageURL),
                   let image = UIImage(data: imageData) {
                    let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in
                        return image
                    }
                    DispatchQueue.main.async {
                        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                        info[MPMediaItemPropertyArtwork] = artwork
                        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
                    }
                }
            }
        }
    }
    
    func disableRemoteTransportControls() {
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.playCommand.isEnabled = false
        commandCenter.pauseCommand.isEnabled = false
        commandCenter.togglePlayPauseCommand.isEnabled = false
        commandCenter.changePlaybackPositionCommand.isEnabled = false
        commandCenter.skipForwardCommand.isEnabled = false
        commandCenter.skipBackwardCommand.isEnabled = false
    }

    func enableRemoteTransportControls(enableSeek: Bool = false) {
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.playCommand.isEnabled = true
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        if (enableSeek) {
            commandCenter.skipForwardCommand.isEnabled = true
            commandCenter.skipBackwardCommand.isEnabled = true
        } else {
            commandCenter.skipForwardCommand.isEnabled = false
            commandCenter.skipBackwardCommand.isEnabled = false
        }
    }
    
    func setupRemoteTransportControls() {
        let commandCenter = MPRemoteCommandCenter.shared()
        
        // Play command
        commandCenter.playCommand.addTarget { event in
            self.implementation.resume()
            // Update Now Playing rate to reflect playing state
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            return .success
        }
        
        // Pause command
        commandCenter.pauseCommand.addTarget { event in
            self.implementation.pause()
            // Update Now Playing rate to reflect paused state
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPNowPlayingInfoPropertyPlaybackRate] = 0.0
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            return .success
        }

        // toggle play/pause command
        commandCenter.togglePlayPauseCommand.addTarget { event in
            if self.implementation.isPlaying() {
                self.implementation.pause()
                var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                info[MPNowPlayingInfoPropertyPlaybackRate] = 0.0
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            } else {
                self.implementation.resume()
                var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
            return .success
        }

        commandCenter.skipForwardCommand.addTarget { event in
            self.implementation.seekBy(offset: 10)
            return .success
        }

        commandCenter.skipBackwardCommand.addTarget { event in
            self.implementation.seekBy(offset: -10)
            return .success
        }

        commandCenter.changePlaybackPositionCommand.addTarget { event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let newTime = event.positionTime
            self.implementation.seekTo(position: newTime)
            // Update elapsed time so the scrubber reflects the new position
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = newTime
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            return .success
        }
    }

}
