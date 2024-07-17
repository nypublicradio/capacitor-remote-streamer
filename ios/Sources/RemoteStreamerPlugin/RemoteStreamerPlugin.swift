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
        CAPPluginMethod(name: "seekTo", returnType: CAPPluginReturnPromise)
    ]
    
    private let implementation = RemoteStreamer()
    
    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(handlePlayEvent), name: Notification.Name("RemoteStreamerPlay"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handlePauseEvent), name: Notification.Name("RemoteStreamerPause"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleStopEvent), name: Notification.Name("RemoteStreamerStop"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleTimeUpdateEvent), name: Notification.Name("RemoteStreamerTimeUpdate"), object: nil)
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
        
        implementation.play(url: url) { result in
            switch result {
            case .success:
                call.resolve()
            case .failure(let error):
                call.reject(error.localizedDescription)
            }
        }
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

    func setupRemoteTransportControls() {
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
    }

}
