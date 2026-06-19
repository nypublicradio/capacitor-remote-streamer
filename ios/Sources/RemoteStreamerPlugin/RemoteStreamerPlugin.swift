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
        CAPPluginMethod(name: "setNowPlayingInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setVolume", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "releasePlayer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setMediaItems", returnType: CAPPluginReturnPromise)
    ]
    
    private var implementation: RemoteStreamer { RemoteStreamer.shared }

    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(handlePlayEvent), name: Notification.Name("RemoteStreamerPlay"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handlePauseEvent), name: Notification.Name("RemoteStreamerPause"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleStopEvent), name: Notification.Name("RemoteStreamerStop"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleEndedEvent), name: Notification.Name("RemoteStreamerEnded"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleTimeUpdateEvent), name: Notification.Name("RemoteStreamerTimeUpdate"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleBufferingEvent), name: Notification.Name("RemoteStreamerBuffering"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleCarPlayPlayRequest), name: Notification.Name("CarPlayPlayRequest"), object: nil)

        // Initialize CarPlayMediaManager early so it can receive CarPlay connection notifications
        if #available(iOS 14.0, *) {
            _ = CarPlayMediaManager.shared
        }
    }

    @objc func handlePlayEvent() {
        notifyListeners("play", data: nil)
        MPNowPlayingInfoCenter.default().playbackState = .playing
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPNowPlayingInfoPropertyPlaybackRate] = 1.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    @objc func handlePauseEvent() {
        notifyListeners("pause", data: nil)
        MPNowPlayingInfoCenter.default().playbackState = .paused
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        info[MPNowPlayingInfoPropertyPlaybackRate] = 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    @objc func handleStopEvent() {
            notifyListeners("stop", data: nil)
            MPNowPlayingInfoCenter.default().playbackState = .stopped
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
        guard let userInfo = notification.userInfo else { return }

        // CarPlayMediaManager already started playback and set Now Playing info.
        // This handler only needs to notify the JS layer so the app UI can update.
        let mediaId = userInfo["id"] as? String ?? ""
        let isLive = userInfo["isLive"] as? Bool ?? false

        notifyListeners("playFromCarPlay", data: ["id": mediaId, "isLive": isLive])
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
        implementation.disableCommandCenter()
    }

    func enableRemoteTransportControls(enableSeek: Bool = false) {
        implementation.enableCommandCenter(seekEnabled: enableSeek)
    }

}
