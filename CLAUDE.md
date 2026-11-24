# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`@nypublicradio/capacitor-remote-streamer`: a Capacitor 8 plugin that streams remote HLS/MP3 audio on iOS (AVPlayer), Android (AndroidX Media3 ExoPlayer) and web (`<audio>` + hls.js), with lock-screen/notification controls, background playback and automatic reconnects. It is app-agnostic. The main consumer is the `wnyc-vue3` app, and WNYC's CarPlay/Android Auto experience lives in the separate `@nypublicradio/wnyc-auto` plugin, which drives this player through its native API. [MIGRATION.md](MIGRATION.md) lists what moved out of this repo in that split.

## Commands

```bash
npm run build          # clean, docgen (rewrites README API section), tsc, rollup → dist/
npm run lint           # eslint + prettier --check + swiftlint
npm run fmt            # autofix all three
npm run verify:android # cd android && ./gradlew clean build test
npm run verify:ios     # xcodebuild build (macOS only)

# Single Android unit test (needs node_modules: settings.gradle pulls :capacitor-android from it)
cd android && ./gradlew test --tests co.broadcastapp.muckabout.PlayerSettingsTest

# iOS tests (macOS only; test target is declared in Package.swift)
xcodebuild test -scheme NypublicradioCapacitorRemoteStreamer -destination 'platform=iOS Simulator,name=<simulator>' \
  -only-testing:NypublicradioCapacitorRemoteStreamerTests/PlayerSettingsTests
```

The main dev machine is Linux, so iOS code can't be compiled or tested locally. Say so when an iOS change goes unverified.

## Architecture

**JS surface.** [src/definitions.ts](src/definitions.ts) is the single source of truth. It defines the `RemoteStreamerPlugin` API and also augments `@capacitor/cli`'s `PluginsConfig` with the `plugins.RemoteStreamer` config block. Its JSDoc becomes the README's `<docgen-*>` sections, so edit the JSDoc and run `npm run build` instead of editing those README blocks by hand. [src/web.ts](src/web.ts) is the web implementation.

**One shared player per platform.** The Capacitor plugin class is a thin bridge, and the player core is usable without the WebView, so CarPlay/Android Auto can start playback with the app closed.

- **iOS:** `RemoteStreamer.shared` ([RemoteStreamer.swift](ios/Sources/RemoteStreamerPlugin/RemoteStreamer.swift)) owns the AVPlayer, the reconnect logic, the stall watchdog, the network monitor, the remote command center and `NowPlayingController`. It posts every event on `NotificationCenter` (`Notification.Name.remoteStreamer*`). [RemoteStreamerPlugin.swift](ios/Sources/RemoteStreamerPlugin/RemoteStreamerPlugin.swift) only observes those notifications and calls `notifyListeners`. Native code calls the public `RemoteStreamer.shared.play(item: PlayableItem)`, which emits `playFromMediaId`.
- **Android:** [RemoteStreamerPlugin.java](android/src/main/java/co/broadcastapp/muckabout/RemoteStreamerPlugin.java) binds to [RemoteStreamerService.java](android/src/main/java/co/broadcastapp/muckabout/RemoteStreamerService.java), a foreground `MediaBrowserServiceCompat` that owns the ExoPlayer, the `MediaSessionCompat`, the notification and network recovery. Events flow service → `plugin.onPlayerEvent` → JS. `MediaSessionCallback` handles media buttons and session commands.
- **Android extension point:** the `library/` package (`MediaLibraryProvider`, `BrowseItem`, `PlayableItem`) is a public API for car integrations. `ProviderLoader` creates the provider from the `co.broadcastapp.muckabout.MEDIA_LIBRARY_PROVIDER` manifest meta-data. Only plain data types may cross this interface. Without a provider, media browsers are refused.

**Host-controlled settings.** `PlayerSettings` (Swift and Java) reads `plugins.RemoteStreamer` directly from the bundled capacitor config (`CapConfig.loadDefault` on Android, `capacitor.config.json` on iOS), not through the bridge, so it also applies without the WebView. Branding (user agent, default title, notification icon, color, channel name) must never be hardcoded. Add new knobs to the `PluginsConfig` augmentation and to both `PlayerSettings` classes, with neutral defaults.

**Background continuation after an item ends.** Both platforms keep the app alive briefly so JS can start the next item: iOS holds a `UIBackgroundTask` (`endOfItemTask`), and Android stays in the foreground for 30 s and tells the WebView it is still visible (`needsWebView()` / `updateWebViewVisibility()`).

## Cross-platform gotchas

- An API change touches four places: `definitions.ts`, `web.ts`, the iOS `pluginMethods` array plus its `@objc` method, and an Android `@PluginMethod`. A method missing from `pluginMethods` can't be called from JS on iOS.
- Known behavior differences, all documented in the JSDoc: the end of an on-demand item is `ended` on iOS but `stop` with `{ ended: true }` on Android and web, and `setPlaybackRate` is web-only.
- `bundle:<path>` URLs resolve to the host app's bundled web assets (`public/<path>`) on all three platforms.
- The Android package `co.broadcastapp.muckabout` is legacy but public: host manifests reference it. Don't rename it.
- The iOS module name `NypublicradioCapacitorRemoteStreamer` must stay identical in [Package.swift](Package.swift) and the podspec so `import NypublicradioCapacitorRemoteStreamer` works under both SPM and CocoaPods.
- `package.json` `files` controls what gets published. Update it if you add native files outside the listed paths.

## Publishing

Published to GitHub Packages (`npm.pkg.github.com`). CircleCI ([.circleci/config.yml](.circleci/config.yml)) publishes on `vX.Y.Z` tags, and prerelease tags (containing `-`) go to the `next` dist-tag. Pushes to `main` and the `demo` tag publish a throwaway `0.0.0`. Version bumps are manual commits to `package.json`.
