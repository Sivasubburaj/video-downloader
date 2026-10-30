# Video Downloader 1.0.0

First release. A small Windows app that saves YouTube Shorts, Instagram Reels and
Facebook videos as MP4 video or MP3 audio. It also works with most other video sites.

![Light theme](screenshots/ui-light.png)
![Dark theme](screenshots/ui-dark.png)

## Download

- **`VideoDownloader-1.0.0-setup.exe`** (easiest): run it and click through. It installs
  just for you, with no administrator password, and adds a Start menu shortcut and an
  uninstaller.
- **`VideoDownloader-1.0.0-win64.zip`**: unzip it and run `Video Downloader.exe`. Nothing is
  installed.

The SHA-256 checksums are in `SHA256SUMS.txt`.

Requires Windows 10 or 11 (64-bit).

### Android

- **`VideoDownloader-1.0.0-android-arm64-v8a.apk`**: almost all phones from about 2017 on.
- **`VideoDownloader-1.0.0-android-armeabi-v7a.apk`**: older 32-bit phones.

Requires Android 8.0 or later. Open the APK on the phone and allow installing apps from that
source when Android asks. Then share a video from YouTube, Instagram, Facebook or X to
**Video Downloader**, or paste its link. Files go to `Download/Video Downloader`.

## Features

- Paste a link to see the title, thumbnail, channel and length before downloading
- MP4 video (Best, 1080p, 720p, 480p or 360p) or MP3 audio
- Download queue with progress, cancel, and double-click to play
- Login option for Instagram and Facebook posts that need it: Firefox, Chrome, Edge,
  Brave or Opera, or a cookies.txt file
- Modern Windows 11-style interface with Light, Dark and System themes and a matching
  title bar
- The download engine (yt-dlp) updates itself every few days, or with
  "Check for updates"
- Advanced: `"Video Downloader.exe" [--download] <link>` opens the app with a link ready,
  or starts downloading it straight away

## Tested

Tested on Windows 10 22H2 with the packaged build:

- YouTube Short → MP4 (H.264 + AAC) and → MP3
- Instagram Reel → MP4 (H.264, 720×1280)
- Facebook video → MP4, including a title with accents and an emoji
- Cancelling mid-download leaves no partial files
- A bad link shows a plain-English error
- First run from an empty settings folder; the app closes cleanly with no leftover processes
- Installer: clicked through the wizard, launched the app from the last page, downloaded
  from the installed copy, then uninstalled. That removed the app, its shortcuts, its
  Apps & features entry and its settings, and kept the downloaded videos.

Android, tested on the Android 14 emulator, including the signed release build:

- Links shared from the share menu, pasted, or typed
- YouTube Short → MP4 (H.264 + AAC) and → MP3, Instagram Reel → MP4 at 720p, Facebook
  video → MP4 with a long, punctuation-heavy title
- A 10-minute video at 360p, downloaded while the app was in the background, with a
  "saved" notification
- Cancelling leaves no partial or temporary files; clear messages for bad links
- Light and dark themes; yt-dlp updates itself on first launch

## Known limitations

- **Unsigned app.** Windows SmartScreen may say "Windows protected your PC" on first
  launch. Click *More info* → *Run anyway*.
- **Chrome and Edge logins** usually can't be read, because those browsers encrypt them. Use
  Firefox, or export a cookies.txt file.
- **Facebook** often serves only VP9 video at its higher resolutions. The MP4 plays in
  Windows' Media Player, VLC and browsers, but some older devices may not play it.
- **YouTube** formats are most complete when Node.js or Deno is installed. The app uses
  either one automatically if it's present.
- Private videos, and videos you can't see in your browser, can't be downloaded.
- **Android** isn't on Google Play, which doesn't allow YouTube downloaders. On a phone,
  previews take about 10 seconds and YouTube downloads about 20 seconds extra, because the
  app solves YouTube's JavaScript challenge with a small built-in engine (QuickJS).
- **Android sign-in** works inside the app for Instagram, Facebook and X. It was checked up
  to the sign-in page, not with a real account. YouTube sign-in isn't offered because Google
  blocks it inside apps.

Only download videos you own, have permission to use, or that are licensed for reuse.

## What's inside

yt-dlp 2026.10.26 (Unlicense), FFmpeg 7.1 (GPLv3, gyan.dev build), Python 3.14,
Tcl/Tk 8.6, Pillow and the Sun Valley ttk theme. Full details are in
`THIRD-PARTY-NOTICES.txt` inside the zip.

The Android app is built on youtubedl-android 0.18.1 (GPLv3), which bundles Python 3.12,
FFmpeg 7.1.1 and QuickJS. The APKs are therefore distributed under the GPLv3; their source is
this repository plus [youtubedl-android](https://github.com/yausername/youtubedl-android).
