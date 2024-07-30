import type { PluginListenerHandle } from '@capacitor/core';

export interface RemoteStreamerPlugin {
  play(options: { url: string, enableCommandCenter?: boolean, enableCommandCenterSeek?: boolean }): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  seekTo(options: { position: number }): Promise<void>;
  stop(): Promise<void>;
  setPlaybackRate(options: { rate: number }): Promise<void>;
  setNowPlayingInfo(options: { title: string; artist: string; album: string; duration: string; imageUrl: string }): Promise<void>;
  addListener(
    eventName: 'play' | 'pause' | 'stop' | 'timeUpdate' | 'buffering' | 'error',
    listenerFunc: (data: RemoteStreamerEventData) => void
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

export type RemoteStreamerEventData =
  | PlayEvent
  | PauseEvent
  | StopEvent
  | TimeUpdateEvent
  | BufferingEvent
  | ErrorEvent;

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