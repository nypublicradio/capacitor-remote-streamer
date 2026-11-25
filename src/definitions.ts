/// <reference types="@capacitor/cli" />

import type { PluginListenerHandle } from '@capacitor/core'

declare module '@capacitor/cli' {
  export interface PluginsConfig {
    /**
     * Branding and settings for the player, owned by the host app.
     * Read natively at startup, so they also apply when playback starts
     * without the WebView (e.g. from CarPlay or Android Auto).
     */
    RemoteStreamer?: {
      /**
       * User-Agent sent with every stream request, per platform.
       * Tokens: `{appName}`, `{appVersion}`, `{osVersion}`, `{deviceModel}`.
       *
       * @default "{appName}/{appVersion} (iOS {osVersion}; {deviceModel})" / "{appName}/{appVersion} (Android {osVersion}; {deviceModel})"
       * @example { "ios": "MyApp/iOS {appVersion}; {deviceModel}; iOS {osVersion}", "android": "MyApp/{appVersion} (Android {osVersion}; {deviceModel})" }
       */
      userAgent?: {
        ios?: string
        android?: string
      }

      /**
       * Title shown on the lock screen / notification when the current item has none.
       *
       * @default ""
       * @example "My Station"
       */
      defaultTitle?: string

      /**
       * Android-only settings. `notification.smallIcon` names a drawable in the app's
       * `res/drawable` (falls back to a neutral music-note icon); `notification.iconColor`
       * is any value `android.graphics.Color.parseColor` accepts; `notification.channelName`
       * defaults to "Playback".
       *
       * @example { "notification": { "smallIcon": "ic_stat_my_station", "iconColor": "#de1e3d", "channelName": "Playback" } }
       */
      android?: {
        notification?: {
          /**
           * Name of a drawable in the app's `res/drawable` used as the notification's
           * status bar icon. Falls back to a neutral music-note icon.
           *
           * @example "ic_stat_notification"
           */
          smallIcon?: string

          /**
           * Notification accent color (any value `android.graphics.Color.parseColor` accepts).
           *
           * @example "#de1e3d"
           */
          iconColor?: string

          /**
           * Name of the notification channel shown in system settings.
           *
           * @default "Playback"
           */
          channelName?: string
        }
      }
    }
  }
}

export interface RemoteStreamerPlugin {
  /**
   * Plays `url`. A `bundle:<path>` URL plays a file bundled with the app's web code
   * (e.g. `bundle:audio/intro.mp3` for `public/audio/intro.mp3`); the promise rejects
   * when that file doesn't exist.
   *
   * The end of an on-demand item is reported as `stop` with `{ ended: true }` on Android
   * and web, and as `ended` on iOS. The app keeps running in the background for a short
   * while after it, so it can start the next item.
   *
   * `enableCommandCenter` and `enableCommandCenterSeek` set up the iOS lock screen controls.
   */
  play (options: { url: string, enableCommandCenter?: boolean, enableCommandCenterSeek?: boolean }): Promise<void>
  pause (): Promise<void>
  /** Resumes. When nothing is loaded (after `stop`, `releasePlayer` or the end), plays the last item again. */
  resume (): Promise<void>
  /** `position` in seconds. */
  seekTo (options: { position: number }): Promise<void>
  stop (): Promise<void>
  setVolume (options: { volume: number }): Promise<void>
  /** Web only. */
  setPlaybackRate (options: { rate: number }): Promise<void>
  /**
   * Lock screen, notification and car display metadata for what's playing. `duration` (seconds)
   * only stands in until the player has measured the audio. Native platforms load `imageUrl`
   * in the background.
   */
  setNowPlayingInfo (options: { title: string; artist: string; album: string; duration: string | number; imageUrl: string; isLiveStream: boolean }): Promise<void>
  /** Unloads the player, e.g. before playing something else. Reported as `stop`. */
  releasePlayer (): Promise<void>
  getCurrentState (): Promise<PlayerState>
  addListener (
    eventName: 'play' | 'pause' | 'stop' | 'ended' | 'ready' | 'timeUpdate' | 'buffering' | 'error' | 'id3Metadata' | 'playFromMediaId',
    listenerFunc: (data: RemoteStreamerEventData) => void
  ): Promise<PluginListenerHandle>
  removeAllListeners (): Promise<void>
}

export type RemoteStreamerEventData =
  | PlayEvent
  | PauseEvent
  | StopEvent
  | EndedEvent
  | ReadyEvent
  | TimeUpdateEvent
  | BufferingEvent
  | ErrorEvent
  | ID3MetadataEvent
  | PlayFromMediaIdEvent

export interface PlayEvent {
  type: 'play'
}

export interface PauseEvent {
  type: 'pause'
}

export interface StopEvent {
  type: 'stop'
  /** True at the end of an on-demand item (Android and web; iOS sends `ended`). */
  ended?: boolean
}

export interface EndedEvent {
  type: 'ended'
  ended: boolean
}

export interface ReadyEvent {
  type: 'ready'
  /** Measured length in seconds; 0 for live streams or when unknown. */
  duration: number
  currentTime: number
  isLiveStream: boolean
}

export interface TimeUpdateEvent {
  type: 'timeUpdate'
  currentTime: number
  duration: number
}

/** Playback waits for data (`isBuffering: true`), or plays again (`false`). Also sent while offline. */
export interface BufferingEvent {
  type: 'buffering'
  isBuffering: boolean
}

/**
 * The item can't be played. Not sent for problems the player recovers from by itself, such
 * as losing the network: playback then resumes when a network comes back.
 */
export interface ErrorEvent {
  type: 'error'
  message: string
}

export interface ID3MetadataEvent {
  type: 'data'
  message: object
}

/**
 * Playback was started natively rather than from JS: CarPlay, Android Auto,
 * Google Assistant, or a media button with nothing loaded. Use it to show the
 * item in the app's player UI.
 */
export interface PlayFromMediaIdEvent {
  type: 'playFromMediaId'
  mediaId: string
  /** @deprecated Same as `mediaId`. */
  id: string
  isLive: boolean
  /** The stream URL, without any per-request parameters the native side added. */
  streamUrl: string
  title: string
  artist: string
  imageUrl?: string
  /** Duration in seconds, when known. */
  duration?: number
}

export interface PlayerState {
  isPlaying: boolean
  currentUrl: string | null
  currentTime: number
  duration: number
  isLiveStream: boolean
  /** Set when playback was started natively (see `playFromMediaId`). */
  currentMediaId: string | null
  title?: string
  artist?: string
  imageUrl?: string
  streamUrl?: string
}
