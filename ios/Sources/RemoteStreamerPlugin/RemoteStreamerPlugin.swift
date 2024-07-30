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
    ]
    
    private let implementation = RemoteStreamer()
    
    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(handlePlayEvent), name: Notification.Name("RemoteStreamerPlay"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handlePauseEvent), name: Notification.Name("RemoteStreamerPause"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleStopEvent), name: Notification.Name("RemoteStreamerStop"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleEndedEvent), name: Notification.Name("RemoteStreamerEnded"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleTimeUpdateEvent), name: Notification.Name("RemoteStreamerTimeUpdate"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleBufferingEvent), name: Notification.Name("RemoteStreamerBuffering"), object: nil)
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
        }
    }

    @objc func play(_ call: CAPPluginCall) {
        guard let url = call.getString("url") else {
            call.reject("Must provide a URL")
            return
        }

        if (call.getBool("enableCommandCenter", false)) {
            if (call.getBool("enableCommandCenterSeek", false)) {
                setupRemoteTransportControls(enableSeek: true)
            } else {
                setupRemoteTransportControls()
            }
        }
        
        implementation.play(url: url) { result in
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
            album: call.getString("album") ?? "", imageURL: URL(string: url))
        call.resolve()
    }
    
    @objc func pause(_ call: CAPPluginCall) {
        implementation.pause()
        call.resolve()
    }
    
    @objc func resume(_ call: CAPPluginCall) {
        implementation.resume()
        call.resolve()
    }
    
    @objc func stop(_ call: CAPPluginCall) {
        implementation.stop()
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

    func updateNowPlayingInfo(title: String, artist: String, album: String, imageURL: URL?) {
        var nowPlayingInfo = [String: Any]()
        nowPlayingInfo[MPMediaItemPropertyTitle] = title
        nowPlayingInfo[MPMediaItemPropertyArtist] = artist
        nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album
        
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

    func setupRemoteTransportControls(enableSeek: Bool = false) {
        let commandCenter = MPRemoteCommandCenter.shared()
        
        // Play command
        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { event in
            self.implementation.resume()
            return .success
        }
        
        // Pause command
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { event in
            self.implementation.pause()
            return .success
        }

        if (enableSeek) {
            commandCenter.seekForwardCommand.isEnabled = true
            commandCenter.seekForwardCommand.addTarget { event in
                self.implementation.seekBy(offset: 10)
                return .success
            }

            commandCenter.seekBackwardCommand.isEnabled = true
            commandCenter.seekBackwardCommand.addTarget { event in
                self.implementation.seekBy(offset: -10)
                return .success
            }
        }
    }

}
