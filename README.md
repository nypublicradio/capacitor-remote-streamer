# mp3-hls-streaming

Stream remote HLS and MP3 streams on iOS and Android.

## Install

```bash
npm install mp3-hls-streaming
npx cap sync
```

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
* [`setMediaItems(...)`](#setmediaitems)
* [`addListener('error' | 'play' | 'pause' | 'stop' | 'timeUpdate' | 'buffering' | 'id3Metadata' | 'playFromCarPlay' | 'playFromMediaId', ...)`](#addlistenererror--play--pause--stop--timeupdate--buffering--id3metadata--playfromcarplay--playfrommediaid-)
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


### setMediaItems(...)

```typescript
setMediaItems(options: { items: CarMediaItem[]; }) => Promise<void>
```

| Param         | Type                                    |
| ------------- | --------------------------------------- |
| **`options`** | <code>{ items: CarMediaItem[]; }</code> |

--------------------


### addListener('error' | 'play' | 'pause' | 'stop' | 'timeUpdate' | 'buffering' | 'id3Metadata' | 'playFromCarPlay' | 'playFromMediaId', ...)

```typescript
addListener(eventName: 'play' | 'pause' | 'stop' | 'timeUpdate' | 'buffering' | 'error' | 'id3Metadata' | 'playFromCarPlay' | 'playFromMediaId', listenerFunc: (data: RemoteStreamerEventData) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                                                                          |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'error' \| 'play' \| 'pause' \| 'stop' \| 'timeUpdate' \| 'buffering' \| 'id3Metadata' \| 'playFromCarPlay' \| 'playFromMediaId'</code> |
| **`listenerFunc`** | <code>(data: <a href="#remotestreamereventdata">RemoteStreamerEventData</a>) =&gt; void</code>                                                |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

--------------------


### Interfaces


#### CarMediaItem

| Prop            | Type                |
| --------------- | ------------------- |
| **`id`**        | <code>string</code> |
| **`title`**     | <code>string</code> |
| **`artist`**    | <code>string</code> |
| **`imageUrl`**  | <code>string</code> |
| **`streamUrl`** | <code>string</code> |


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


#### TimeUpdateEvent

| Prop              | Type                      |
| ----------------- | ------------------------- |
| **`type`**        | <code>'timeUpdate'</code> |
| **`currentTime`** | <code>number</code>       |


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


#### PlayFromCarPlayEvent

| Prop       | Type                           |
| ---------- | ------------------------------ |
| **`type`** | <code>'playFromCarPlay'</code> |
| **`id`**   | <code>string</code>            |


#### PlayFromMediaIdEvent

| Prop          | Type                           |
| ------------- | ------------------------------ |
| **`type`**    | <code>'playFromMediaId'</code> |
| **`mediaId`** | <code>string</code>            |


### Type Aliases


#### RemoteStreamerEventData

<code><a href="#playevent">PlayEvent</a> | <a href="#pauseevent">PauseEvent</a> | <a href="#stopevent">StopEvent</a> | <a href="#timeupdateevent">TimeUpdateEvent</a> | <a href="#bufferingevent">BufferingEvent</a> | <a href="#errorevent">ErrorEvent</a> | <a href="#id3metadataevent">ID3MetadataEvent</a> | <a href="#playfromcarplayevent">PlayFromCarPlayEvent</a> | <a href="#playfrommediaidevent">PlayFromMediaIdEvent</a></code>

</docgen-api>
