import Foundation
import UIKit

/// Host-controlled settings from `capacitor.config` → `plugins.RemoteStreamer`.
///
/// Read straight from the bundled `capacitor.config.json` rather than through the
/// Capacitor bridge, so they also apply when playback starts without the WebView
/// (for example from CarPlay with the phone app closed).
struct PlayerSettings {
    /// Now Playing title used when the current item has none.
    let defaultTitle: String
    /// User-Agent header sent with every stream request.
    let userAgent: String

    static let shared = PlayerSettings.load()

    static let defaultUserAgentTemplate = "{appName}/{appVersion} (iOS {osVersion}; {deviceModel})"

    static func load(bundle: Bundle = .main) -> PlayerSettings {
        let config = pluginConfig(named: "RemoteStreamer", bundle: bundle)
        let userAgentConfig = config["userAgent"] as? [String: Any]
        let template = nonEmpty(userAgentConfig?["ios"] as? String) ?? defaultUserAgentTemplate

        let info = bundle.infoDictionary ?? [:]
        let userAgent = renderUserAgent(template: template, tokens: [
            "appName": (info["CFBundleName"] as? String) ?? "App",
            "appVersion": (info["CFBundleShortVersionString"] as? String) ?? "Unknown",
            "osVersion": UIDevice.current.systemVersion,
            "deviceModel": UIDevice.current.model
        ])

        return PlayerSettings(
            defaultTitle: nonEmpty(config["defaultTitle"] as? String) ?? "",
            userAgent: userAgent
        )
    }

    /// Replaces `{token}` placeholders in a User-Agent template.
    static func renderUserAgent(template: String, tokens: [String: String]) -> String {
        tokens.reduce(template) { result, token in
            result.replacingOccurrences(of: "{\(token.key)}", with: token.value)
        }
    }

    /// The `plugins.<name>` object from the app's bundled `capacitor.config.json`.
    static func pluginConfig(named name: String, bundle: Bundle) -> [String: Any] {
        guard let url = bundle.url(forResource: "capacitor.config", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let plugins = root["plugins"] as? [String: Any],
              let config = plugins[name] as? [String: Any] else {
            return [:]
        }
        return config
    }

    /// Treats empty strings and the literal "undefined" (an unset env var in
    /// capacitor.config.ts) as missing.
    static func nonEmpty(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty, value != "undefined", value != "null" else {
            return nil
        }
        return value
    }
}
