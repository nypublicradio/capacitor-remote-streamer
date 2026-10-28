import Foundation
import Capacitor

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
        CAPPluginMethod(name: "getCurrentState", returnType: CAPPluginReturnPromise)
    ]

    private var implementation: RemoteStreamer { RemoteStreamer.shared }

    override public func load() {
        // Now Playing (lock screen / CarPlay) is kept in sync by the player core itself;
        // the plugin only forwards events to JS.
        NotificationCenter.default.addObserver(self, selector: #selector(handlePlayEvent), name: .remoteStreamerPlay, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handlePauseEvent), name: .remoteStreamerPause, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleStopEvent), name: .remoteStreamerStop, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleEndedEvent), name: .remoteStreamerEnded, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleTimeUpdateEvent), name: .remoteStreamerTimeUpdate, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleBufferingEvent), name: .remoteStreamerBuffering, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleReadyEvent), name: .remoteStreamerReady, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handlePlayFromMediaIdEvent), name: .remoteStreamerPlayFromMediaId, object: nil)
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

    @objc func handleReadyEvent(notification: Notification) {
        if let userInfo = notification.userInfo {
            let duration = userInfo["duration"] as? Double ?? 0
            let currentTime = userInfo["currentTime"] as? Double ?? 0
            let isLiveStream = userInfo["isLiveStream"] as? Bool ?? false
            notifyListeners("ready", data: [
                "duration": duration,
                "currentTime": currentTime,
                "isLiveStream": isLiveStream
            ])
        }
    }

    @objc func handleTimeUpdateEvent(notification: Notification) {
        if let userInfo = notification.userInfo, let currentTime = userInfo["currentTime"] as? Double {
            var data: [String: Any] = ["currentTime": currentTime]
            if let duration = userInfo["duration"] as? Double, duration > 0 {
                data["duration"] = duration
            } else {
                data["duration"] = 0
            }
            notifyListeners("timeUpdate", data: data)
        }
    }

    /// Playback was started natively (e.g. from CarPlay); lets the app update its UI.
    @objc func handlePlayFromMediaIdEvent(notification: Notification) {
        guard let payload = notification.userInfo as? [String: Any] else { return }
        notifyListeners("playFromMediaId", data: payload)
    }

    @objc func play(_ call: CAPPluginCall) {
        guard let url = call.getString("url") else {
            call.reject("Must provide a URL")
            return
        }

        if call.getBool("enableCommandCenter", false) {
            implementation.enableCommandCenter(seekEnabled: call.getBool("enableCommandCenterSeek", false))
        } else {
            implementation.disableCommandCenter()
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
        let imageUrl = call.getString("imageUrl")
        implementation.setNowPlayingMetadata(
            title: call.getString("title") ?? "",
            artist: call.getString("artist") ?? "",
            album: call.getString("album") ?? "",
            durationSeconds: call.getDouble("duration") ?? Double(call.getString("duration") ?? "") ?? 0,
            artworkUrl: (imageUrl?.isEmpty ?? true) ? nil : imageUrl,
            isLive: call.getBool("isLiveStream", false)
        )
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

    @objc func releasePlayer(_ call: CAPPluginCall) {
        implementation.stop()
        call.resolve()
    }

    @objc func setVolume(_ call: CAPPluginCall) {
        guard let volume = call.getDouble("volume") else {
            call.reject("Must provide a volume")
            return
        }
        implementation.setVolume(volume: volume)
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

    @objc func getCurrentState(_ call: CAPPluginCall) {
        let state = implementation.getCurrentState()
        call.resolve(state as [String: Any])
    }
}
