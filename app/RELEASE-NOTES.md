# Video Downloader 1.0.0

First release. A small Windows app that saves YouTube Shorts, Instagram Reels and
Facebook videos as MP4 video or MP3 audio. It also works with most other video sites.

![Light theme](screenshots/ui-light.png)
![Dark theme](screenshots/ui-dark.png)

## Download

`VideoDownloader-1.0.0-win64.zip`. Unzip it and run `Video Downloader.exe`. Nothing is
installed. The SHA-256 checksum is in `SHA256SUMS.txt`.

Requires Windows 10 or 11 (64-bit).

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

Only download videos you own, have permission to use, or that are licensed for reuse.

## What's inside

yt-dlp 2026.10.26 (Unlicense), FFmpeg 7.1 (GPLv3, gyan.dev build), Python 3.14,
Tcl/Tk 8.6, Pillow and the Sun Valley ttk theme. Full details are in
`THIRD-PARTY-NOTICES.txt` inside the zip.
