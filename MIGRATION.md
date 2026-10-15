# Migrating from 1.x to 2.0

2.0 makes the player app-agnostic. The CarPlay and Android Auto experiences that 1.x shipped for WNYC moved to [`@nypublicradio/wnyc-auto`](https://github.com/nypublicradio/wnyc-auto), which plugs into the player through the new native API.

## Removed

| 1.x | 2.0 |
|---|---|
| `plugins.RemoteStreamer.carExperienceEnabled` | Removed. Installing a car integration (e.g. `wnyc-auto`) turns the car experience on. |
| `plugins.RemoteStreamer.bffBaseUrl` | Removed. Car content configuration belongs to the car integration (`plugins.WnycAuto`). |
| `setMediaItems()` / `CarMediaItem` | Removed (it had no effect on iOS and wasn't used). Car content comes from a native integration. |
| `playFromCarPlay` event (iOS) | Use `playFromMediaId`, now emitted on both platforms with the same payload. |
| iOS `CarPlayMediaManager`, `PluginCarPlaySceneDelegate` | Moved to `wnyc-auto` (`WnycCarPlaySceneDelegate`). |
| Android Auto meta-data (`com.google.android.gms.car.application`) | No longer declared by this plugin, so apps that only use the player don't appear in Android Auto. |
| Hardcoded WNYC branding (notification icon, `WNYC-App` user agent, "WNYC" default title) | Set by the host app under `plugins.RemoteStreamer` (see the README). |

## Changed

- **`playFromMediaId` payload** (both platforms): `{ mediaId, id, isLive, streamUrl, title, artist, imageUrl?, duration? }`. `id` is a deprecated alias of `mediaId`.
- **`getCurrentState()`** also returns `title`, `artist`, `imageUrl` and `streamUrl` for the current item.
- **Android notification** buttons and lock-screen state no longer depend on the WebView being loaded. Live streams no longer offer a seek bar.
- **Android `setVolume()`** now resolves its promise (it previously never settled).
- **iOS module name** is `NypublicradioCapacitorRemoteStreamer` under both CocoaPods and Swift Package Manager. Package.swift's product was renamed from `Mp3HlsStreaming`, which broke SPM-based apps.

## Host app checklist

1. Move car config out of `plugins.RemoteStreamer`.
2. Add the branding you want to keep:

   ```ts
   RemoteStreamer: {
     userAgent: {
       ios: 'WNYC-App/iOS {appVersion}; {deviceModel}; iOS {osVersion}',
       android: 'WNYC-App/{appVersion} (Android {osVersion}; {deviceModel})',
     },
     defaultTitle: 'WNYC',
     android: { notification: { smallIcon: 'ic_stat_wnyc', iconColor: '#de1e3d' } },
   }
   ```

   Put the icon drawable in `android/app/src/main/res/drawable/`.
3. Replace `addListener('playFromCarPlay', …)` with `addListener('playFromMediaId', …)`.
4. Remove iOS host code that referenced `CarPlayMediaManager`.
