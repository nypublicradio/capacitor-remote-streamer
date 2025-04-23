import { WebPlugin } from '@capacitor/core';
import Hls from 'hls.js';

import type { RemoteStreamerPlugin } from './definitions';

export class RemoteStreamerWeb extends WebPlugin implements RemoteStreamerPlugin {
  private audio: HTMLAudioElement | null = null;
  private intervalId: number | null = null;
  private hls: Hls | null = null;

  async setNowPlayingInfo(options: { title: string; artist: string; album: string; duration: string; imageUrl: string; }): Promise<void> {
    console.log("Setting now playing info", options);
  }

  async enableCommandCenter(options: { seek: boolean; }): Promise<void> {
    console.log("Enabling lock screen control", options);
  }

  async play(options: { url: string }): Promise<void> {
    if (this.audio) {
      this.audio.pause();
    }
    this.audio = new Audio();
    this.audio.id = "pluginAudioElement"; // Assigning an ID to the audio element
    this.setupEventListeners(); // Call setupEventListeners here

    const urlWithoutParams = options.url.split('?')[0];
    if (Hls.isSupported() && urlWithoutParams.endsWith('.m3u8')) {
      this.hls = new Hls();
      this.hls.loadSource(options.url);
      this.hls.attachMedia(this.audio);
      this.hls.on(Hls.Events.MANIFEST_PARSED, async () => {
        if (this.audio) {
          await this.audio.play();
        }
        this.notifyListeners('play', {});
        this.startTimeUpdates();
        if (this.hls) {
          this.setupID3Listeners(this.hls);
        }
      });
    } else {
      this.audio.src = options.url;
      await this.audio.play();
      this.notifyListeners('play', {});
      this.startTimeUpdates();
    }
  }

  async pause(): Promise<void> {
    if (this.audio) {
      this.audio.pause();
      this.notifyListeners('pause', {});
    }
  }

  async resume(): Promise<void> {
    if (this.audio) {
      await this.audio.play();
      this.notifyListeners('play', {});
    }
  }

  async seekTo(options: { position: number }): Promise<void> {
    if (this.audio) {
      this.audio.currentTime = options.position;
    }
  }

  async stop(): Promise<void> {
    if (this.audio) {
      this.audio.pause();
      this.audio.src = '';
      this.audio.load();
      this.audio.currentTime = 0;
      this.audio = null;
      if (this.hls) {
        this.hls.destroy();
        this.hls = null;
      }
      this.notifyListeners('stop', {});
      this.stopTimeUpdates();
      console.log('stopped', this.audio);
    }
  }

  async setPlaybackRate(options: { rate: number }): Promise<void> {
    if (this.audio) {
      this.audio.playbackRate = options.rate;
    }
  }

  private startTimeUpdates() {
    this.stopTimeUpdates();
    this.intervalId = window.setInterval(() => {
      if (this.audio) {
        this.notifyListeners('timeUpdate', { currentTime: this.audio.currentTime });
      }
    }, 1000);
  }

  private stopTimeUpdates() {
    if (this.intervalId !== null) {
      window.clearInterval(this.intervalId);
      this.intervalId = null;
    }
  }

  async setVolume(options: { volume: number }): Promise<void> {
    if (this.audio) {
      this.audio.volume = options.volume;
    }
  }

  private setupID3Listeners(hls: Hls) {
    if (hls) {
      hls.on(Hls.Events.FRAG_PARSING_METADATA, (_, data) => {
        this.notifyListeners('id3Metadata', data);
      });
    }
  }

  private setupEventListeners() {
    if (this.audio) {
      this.audio.onplaying = () => this.notifyListeners('play', {});
      this.audio.onpause = () => this.notifyListeners('pause', {});
      this.audio.onended = () => this.notifyListeners('stop', { ended: true });
      this.audio.onerror = (e) => this.notifyListeners('error', { message: `Audio error: ${e}` });
      this.audio.onwaiting = () => this.notifyListeners('buffering', { isBuffering: true });
      this.audio.oncanplaythrough = () => this.notifyListeners('buffering', { isBuffering: false });

    }
  }
}
