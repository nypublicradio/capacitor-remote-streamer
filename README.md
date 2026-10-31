# @nypublicradio/capacitor-remote-streamer

Stream remote HLS and MP3 audio in Capacitor apps on iOS, Android and the web, with lock-screen / notification controls, background playback and automatic reconnects.

The plugin is app-agnostic. Branding comes from your app's `capacitor.config`, and native integrations such as CarPlay or Android Auto plug into the same player through a small native API (see [Native integrations](#native-integrations)). WNYC's car experience lives in [`@nypublicradio/wnyc-auto`](https://github.com/nypublicradio/wnyc-auto).

Upgrading from 1.x? See [MIGRATION.md](MIGRATION.md).

## Install

```bash
npm install @nypublicradio/capacitor-remote-streamer
npx cap sync
```

## Configuration

Everything app-specific (branding, colors, user agent) is set by the host app under `plugins.RemoteStreamer`. The values are read natively from the bundled config, so they also apply when playback starts without the WebView.

<docgen-config>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

Branding and settings for the player, owned by the host app.
Read natively at startup, so they also apply when playback starts
without the WebView (e.g. from CarPlay or Android Auto).

| Prop               | Type                                                                                               | Description                                                                                                                                                                                                                                                                   | Default                                                                                                                                |
| ------------------ | -------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| **`userAgent`**    | <code>{ ios?: string; android?: string; }</code>                                                   | User-Agent sent with every stream request, per platform. Tokens: `{appName}`, `{appVersion}`, `{osVersion}`, `{deviceModel}`.                                                                                                                                                 | <code>"{appName}/{appVersion} (iOS {osVersion}; {deviceModel})" / "{appName}/{appVersion} (Android {osVersion}; {deviceModel})"</code> |
| **`defaultTitle`** | <code>string</code>                                                                                | Title shown on the lock screen / notification when the current item has none.                                                                                                                                                                                                 | <code>""</code>                                                                                                                        |
| **`android`**      | <code>{ notification?: { smallIcon?: string; iconColor?: string; channelName?: string; }; }</code> | Android-only settings. `notification.smallIcon` names a drawable in the app's `res/drawable` (falls back to a neutral music-note icon); `notification.iconColor` is any value `android.graphics.Color.parseColor` accepts; `notification.channelName` defaults to "Playback". |                                                                                                                                        |

### Examples

In `capacitor.config.json`:

```json
{
  "plugins": {
    "RemoteStreamer": {
      "userAgent": { "ios": "MyApp/iOS {appVersion}; {deviceModel}; iOS {osVersion}", "android": "MyApp/{appVersion} (Android {osVersion}; {deviceModel})" },
      "defaultTitle": "My Station",
      "android": { "notification": { "smallIcon": "ic_stat_my_station", "iconColor": "#de1e3d", "channelName": "Playback" } }
    }
  }
}
```

In `capacitor.config.ts`:

```ts
/// <reference types="@nypublicradio/capacitor-remote-streamer" />

import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  plugins: {
    RemoteStreamer: {
      userAgent: { "ios": "MyApp/iOS {appVersion}; {deviceModel}; iOS {osVersion}", "android": "MyApp/{appVersion} (Android {osVersion}; {deviceModel})" },
      defaultTitle: "My Station",
      android: { "notification": { "smallIcon": "ic_stat_my_station", "iconColor": "#de1e3d", "channelName": "Playback" } },
    },
  },
};

export default config;
```

</docgen-config>

The Android `smallIcon` is looked up by name in your app's `res/drawable`. If your app enables `shrinkResources`, keep it with `tools:keep="@drawable/ic_stat_my_station"` in `res/raw/keep.xml`.

## Native integrations

Native code in the same app (for example a CarPlay or Android Auto integration) can drive the player directly. Playback started this way updates the lock screen, notification and car Now Playing screens, and reaches JS as a `playFromMediaId` event. `getCurrentState()` also reports the item, so the app can catch up when it opens later.

### iOS

```swift
import NypublicradioCapacitorRemoteStreamer

RemoteStreamer.shared.play(item: PlayableItem(
    mediaId: "live:my-station",
    url: "https://example.org/live.m3u8",
    title: "My Station",
    artist: "Live",
    artworkUrl: "https://example.org/logo.png",
    isLive: true
))
```

`PlayableItem.canonicalUrl` is the URL reported to JS when `url` carries extra per-request parameters. Player events are also posted on `NotificationCenter` (`Notification.Name.remoteStreamerPlay`, `.remoteStreamerPause`, and so on).

### Android

The player's `RemoteStreamerService` is a media browser service. To serve Android Auto (or Google Assistant) from it, implement `co.broadcastapp.muckabout.library.MediaLibraryProvider` and name the class in your manifest:

```xml
<application>
  <meta-data
      android:name="co.broadcastapp.muckabout.MEDIA_LIBRARY_PROVIDER"
      android:value="com.example.MyMediaLibraryProvider" />
</application>
```

The service creates the provider itself (public no-arg constructor), so this works when a car connects with the app closed. Without a provider, media browsers are refused. The provider returns plain `BrowseItem` and `PlayableItem` objects; tapping, voice search and "play" with nothing loaded all go through it. Showing up in Android Auto also needs the `com.google.android.gms.car.application` meta-data in your app, which this plugin deliberately doesn't declare.

## API

<docgen-index>

* [`play(...)`](#play)
* [`pause()`](#pause)
* [`resume()`](#resume)
* [`seekTo(...)`](#seekto)
* [`stop()`](#stop)
* [`setVolume(...)`](#setvolume)
* [`setPlaybackRate(...)`](#setplaybackrate)
* [`setNowPlayingInfo(...)`](#setnowplayinginfo)
* [`releasePlayer()`](#releaseplayer)
* [`getCurrentState()`](#getcurrentstate)
* [`addListener('error' | 'play' | 'pause' | 'stop' | 'ended' | 'ready' | 'timeUpdate' | 'buffering' | 'id3Metadata' | 'playFromMediaId', ...)`](#addlistenererror--play--pause--stop--ended--ready--timeupdate--buffering--id3metadata--playfrommediaid-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### play(...)

```typescript
play(options: { url: string; enableCommandCenter?: boolean; enableCommandCenterSeek?: boolean; }) => Promise<void>
```

Plays `url`. A `bundle:&lt;path&gt;` URL plays a file bundled with the app's web code
(e.g. `bundle:audio/intro.mp3` for `public/audio/intro.mp3`); the promise rejects
when that file doesn't exist.

The end of an on-demand item is reported as `stop` with `{ ended: true }` on Android
and web, and as `ended` on iOS. The app keeps running in the background for a short
while after it, so it can start the next item.

| Param         | Type                                                                                            |
| ------------- | ----------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ url: string; enableCommandCenter?: boolean; enableCommandCenterSeek?: boolean; }</code> |

--------------------


### pause()

```typescript
pause() => Promise<void>
```

--------------------


### resume()

```typescript
resume() => Promise<void>
```

--------------------


### seekTo(...)

```typescript
seekTo(options: { position: number; }) => Promise<void>
```

| Param         | Type                               |
| ------------- | ---------------------------------- |
| **`options`** | <code>{ position: number; }</code> |

--------------------


### stop()

```typescript
stop() => Promise<void>
```

--------------------


### setVolume(...)

```typescript
setVolume(options: { volume: number; }) => Promise<void>
```

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ volume: number; }</code> |

--------------------


### setPlaybackRate(...)

```typescript
setPlaybackRate(options: { rate: number; }) => Promise<void>
```

| Param         | Type                           |
| ------------- | ------------------------------ |
| **`options`** | <code>{ rate: number; }</code> |

--------------------


### setNowPlayingInfo(...)

```typescript
setNowPlayingInfo(options: { title: string; artist: string; album: string; duration: string; imageUrl: string; isLiveStream: boolean; }) => Promise<void>
```

| Param         | Type                                                                                                                      |
| ------------- | ------------------------------------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ title: string; artist: string; album: string; duration: string; imageUrl: string; isLiveStream: boolean; }</code> |

--------------------


### releasePlayer()

```typescript
releasePlayer() => Promise<void>
```

--------------------


### getCurrentState()

```typescript
getCurrentState() => Promise<PlayerState>
```

**Returns:** <code>Promise&lt;<a href="#playerstate">PlayerState</a>&gt;</code>

--------------------


### addListener('error' | 'play' | 'pause' | 'stop' | 'ended' | 'ready' | 'timeUpdate' | 'buffering' | 'id3Metadata' | 'playFromMediaId', ...)

```typescript
addListener(eventName: 'play' | 'pause' | 'stop' | 'ended' | 'ready' | 'timeUpdate' | 'buffering' | 'error' | 'id3Metadata' | 'playFromMediaId', listenerFunc: (data: RemoteStreamerEventData) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                                                                           |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'error' \| 'play' \| 'pause' \| 'stop' \| 'ended' \| 'ready' \| 'timeUpdate' \| 'buffering' \| 'id3Metadata' \| 'playFromMediaId'</code> |
| **`listenerFunc`** | <code>(data: <a href="#remotestreamereventdata">RemoteStreamerEventData</a>) =&gt; void</code>                                                 |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

--------------------


### Interfaces


#### PlayerState

| Prop                 | Type                        | Description                                                     |
| -------------------- | --------------------------- | --------------------------------------------------------------- |
| **`isPlaying`**      | <code>boolean</code>        |                                                                 |
| **`currentUrl`**     | <code>string \| null</code> |                                                                 |
| **`currentTime`**    | <code>number</code>         |                                                                 |
| **`duration`**       | <code>number</code>         |                                                                 |
| **`isLiveStream`**   | <code>boolean</code>        |                                                                 |
| **`currentMediaId`** | <code>string \| null</code> | Set when playback was started natively (see `playFromMediaId`). |
| **`title`**          | <code>string</code>         |                                                                 |
| **`artist`**         | <code>string</code>         |                                                                 |
| **`imageUrl`**       | <code>string</code>         |                                                                 |
| **`streamUrl`**      | <code>string</code>         |                                                                 |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### PlayEvent

| Prop       | Type                |
| ---------- | ------------------- |
| **`type`** | <code>'play'</code> |


#### PauseEvent

| Prop       | Type                 |
| ---------- | -------------------- |
| **`type`** | <code>'pause'</code> |


#### StopEvent

| Prop       | Type                |
| ---------- | ------------------- |
| **`type`** | <code>'stop'</code> |


#### EndedEvent

| Prop        | Type                 |
| ----------- | -------------------- |
| **`type`**  | <code>'ended'</code> |
| **`ended`** | <code>boolean</code> |


#### ReadyEvent

| Prop               | Type                 |
| ------------------ | -------------------- |
| **`type`**         | <code>'ready'</code> |
| **`duration`**     | <code>number</code>  |
| **`currentTime`**  | <code>number</code>  |
| **`isLiveStream`** | <code>boolean</code> |


#### TimeUpdateEvent

| Prop              | Type                      |
| ----------------- | ------------------------- |
| **`type`**        | <code>'timeUpdate'</code> |
| **`currentTime`** | <code>number</code>       |
| **`duration`**    | <code>number</code>       |


#### BufferingEvent

| Prop              | Type                     |
| ----------------- | ------------------------ |
| **`type`**        | <code>'buffering'</code> |
| **`isBuffering`** | <code>boolean</code>     |


#### ErrorEvent

| Prop          | Type                 |
| ------------- | -------------------- |
| **`type`**    | <code>'error'</code> |
| **`message`** | <code>string</code>  |


#### ID3MetadataEvent

| Prop          | Type                |
| ------------- | ------------------- |
| **`type`**    | <code>'data'</code> |
| **`message`** | <code>object</code> |


#### PlayFromMediaIdEvent

Playback was started natively rather than from JS: CarPlay, Android Auto,
Google Assistant, or a media button with nothing loaded. Use it to show the
item in the app's player UI.

| Prop            | Type                           | Description                                                               |
| --------------- | ------------------------------ | ------------------------------------------------------------------------- |
| **`type`**      | <code>'playFromMediaId'</code> |                                                                           |
| **`mediaId`**   | <code>string</code>            |                                                                           |
| **`id`**        | <code>string</code>            |                                                                           |
| **`isLive`**    | <code>boolean</code>           |                                                                           |
| **`streamUrl`** | <code>string</code>            | The stream URL, without any per-request parameters the native side added. |
| **`title`**     | <code>string</code>            |                                                                           |
| **`artist`**    | <code>string</code>            |                                                                           |
| **`imageUrl`**  | <code>string</code>            |                                                                           |
| **`duration`**  | <code>number</code>            | Duration in seconds, when known.                                          |


### Type Aliases


#### RemoteStreamerEventData

<code><a href="#playevent">PlayEvent</a> | <a href="#pauseevent">PauseEvent</a> | <a href="#stopevent">StopEvent</a> | <a href="#endedevent">EndedEvent</a> | <a href="#readyevent">ReadyEvent</a> | <a href="#timeupdateevent">TimeUpdateEvent</a> | <a href="#bufferingevent">BufferingEvent</a> | <a href="#errorevent">ErrorEvent</a> | <a href="#id3metadataevent">ID3MetadataEvent</a> | <a href="#playfrommediaidevent">PlayFromMediaIdEvent</a></code>

</docgen-api>
