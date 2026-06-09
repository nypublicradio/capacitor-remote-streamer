import type { PluginListenerHandle } from '@capacitor/core';

export interface RemoteStreamerPlugin {
  play(options: { url: string, enableCommandCenter?: boolean, enableCommandCenterSeek?: boolean }): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  seekTo(options: { position: number }): Promise<void>;
  stop(): Promise<void>;
  setVolume(options: { volume: number }): Promise<void>;
  setPlaybackRate(options: { rate: number }): Promise<void>;
  setNowPlayingInfo(options: { title: string; artist: string; album: string; duration: string; imageUrl: string; isLiveStream: boolean }): Promise<void>;
  releasePlayer(): Promise<void>;
  setMediaItems(options: { items: CarMediaItem[] }): Promise<void>;
  addListener(
    eventName: 'play' | 'pause' | 'stop' | 'timeUpdate' | 'buffering' | 'error' | 'id3Metadata' | 'playFromCarPlay' | 'playFromMediaId',
    listenerFunc: (data: RemoteStreamerEventData) => void
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

export interface CarMediaItem {
  id: string;
  title: string;
  artist: string;
  imageUrl: string;
  streamUrl: string;
}

export type RemoteStreamerEventData =
  | PlayEvent
  | PauseEvent
  | StopEvent
  | TimeUpdateEvent
  | BufferingEvent
  | ErrorEvent
  | ID3MetadataEvent
  | PlayFromCarPlayEvent
  | PlayFromMediaIdEvent;

export interface PlayEvent {
  type: 'play';
}

export interface PauseEvent {
  type: 'pause';
}

export interface StopEvent {
  type: 'stop';
}

export interface TimeUpdateEvent {
  type: 'timeUpdate';
  currentTime: number;
}

export interface BufferingEvent {
  type: 'buffering';
  isBuffering: boolean;
}

export interface ErrorEvent {
  type: 'error';
  message: string;
}

export interface ID3MetadataEvent {
  type: 'data';
  message: object;
}

export interface PlayFromCarPlayEvent {
  type: 'playFromCarPlay';
  id: string;
}

export interface PlayFromMediaIdEvent {
  type: 'playFromMediaId';
  mediaId: string;
}