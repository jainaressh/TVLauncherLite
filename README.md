# TV Launcher Lite

A lightweight home screen (launcher) for Android TV and Google TV devices, built with plain Android framework APIs:
no AndroidX, no third-party libraries. The release APK is about 0.5 MB and it is designed to keep RAM use low on
budget TVs.

## Features

- **Home screen**: category tabs, search, a big clock heading, an app grid and a bottom quick bar.
- **App cards** coloured from each app's own icon or TV banner; focus grows and lifts the card with a glow.
- **Dark, Light and Auto themes** (Auto is light from 7 AM to 7 PM).
- **Wallpapers**: drawn gradient backdrops (no bitmap in memory), two built-in photos, solid colours, or your own
  image from internal storage, SD card or USB drive. Frosted-glass panels without live blur.
- **TV inputs**: HDMI / AV / tuner sources on TVs with built-in Android, with per-input show/hide.
- **Customisable layout**: card size, moving cards, quick-bar apps.
- **Downloads screen**: enter a URL or a short-link number code, a D-pad pointer browser, and a file list that
  hands APKs to the system installer.
- **Network status** (Wi-Fi / Ethernet / offline) in the top bar.

## Memory design

- Settings, the image browser and the downloads screen each run in **their own process**, which ends when the
  screen closes, so the web engine and settings UI never sit in the home screen's memory.
- App images are loaded and shrunk on a background thread to the size they are shown at.
- Only the active theme's wallpaper is kept in memory.

## Requirements

- Android 11 (API 30) or newer, TV or box with a D-pad remote.
- To build: JDK 17 and the Android SDK (compileSdk 35).

## Build

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. The release build is signed with the debug key so it can
be sideloaded straight away; use your own signing key before distributing it.

## Install and set as Home

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then press the Home button on the remote and choose **TV Launcher Lite**, or pick it under the TV's
*Settings → Apps → Default apps → Home app* (wording varies by TV maker).

## Credits

The built-in wallpaper photos are public domain / CC0; see [WALLPAPER_CREDITS.md](WALLPAPER_CREDITS.md).
