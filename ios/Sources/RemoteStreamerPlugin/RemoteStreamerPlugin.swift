import Foundation
import Capacitor
import MediaPlayer

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
        CAPPluginMethod(name: "setNowPlayingInfo", returnType: CAPPluginReturnPromise)
        CAPPluginMethod(name: "setVolume", returnType: CAPPluginReturnPromise)
    ]
    
    private let implementation = RemoteStreamer()
    
    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(handlePlayEvent), name: Notification.Name("RemoteStreamerPlay"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handlePauseEvent), name: Notification.Name("RemoteStreamerPause"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleStopEvent), name: Notification.Name("RemoteStreamerStop"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleEndedEvent), name: Notification.Name("RemoteStreamerEnded"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleTimeUpdateEvent), name: Notification.Name("RemoteStreamerTimeUpdate"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleSetVolumeEvent), name: Notification.Name("RemoteStreamerSetVolume"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleBufferingEvent), name: Notification.Name("RemoteStreamerBuffering"), object: nil)
        setupRemoteTransportControls()
    }

    @objc func handlePlayEvent() {
        notifyListeners("play", data: nil)
    }

    @objc func handlePauseEvent() {
        notifyListeners("pause", data: nil)
    }

    @objc func handleStopEvent() {
            notifyListeners("stop", data: nil)
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
            MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
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
    
    @objc func handleSetVolumeEvent(_ call: CAPPluginCall) {
        print("set volume")
        implementation.setVolume(call.getFloat("volume") ?? 1.0)
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

    func updateNowPlayingInfo(title: String, artist: String, album: String, duration: String ,imageURL: URL?, isLiveStream: Bool) {
        var nowPlayingInfo = [String: Any]()
        nowPlayingInfo[MPMediaItemPropertyTitle] = title
        nowPlayingInfo[MPMediaItemPropertyArtist] = artist
        nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
        nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = isLiveStream
        
        if let imageURL = imageURL {
            // Load the image from the URL asynchronously
            DispatchQueue.global(qos: .userInitiated).async {
                if let imageData = try? Data(contentsOf: imageURL),
                   let image = UIImage(data: imageData) {
                    let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in
                        return image
                    }
                    DispatchQueue.main.async {
                        nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
                        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
                    }
                }
            }
        } else {
            // Update nowPlayingInfo without artwork
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
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
            return .success
        }
        
        // Pause command
        commandCenter.pauseCommand.addTarget { event in
            self.implementation.pause()
            return .success
        }

        // toggle play/pause command
        commandCenter.togglePlayPauseCommand.addTarget { event in
            if self.implementation.isPlaying() {
                self.implementation.pause()
            } else {
                self.implementation.resume()
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
            return .success
        }
    }

}
