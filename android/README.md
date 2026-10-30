# Video Downloader for Android

The same app as the Windows version, for Android 8.0 and later: paste or share a link, see a
preview, and save it as MP4 (Best, 1080p, 720p, 480p or 360p) or MP3 to
`Download/Video Downloader`.

- **Share → Video Downloader** from YouTube, Instagram, Facebook, X or a browser opens the app
  with the link ready.
- Downloads keep going in the background, with progress in a notification.
- Sign in to Instagram, Facebook or X inside the app (Settings → Logins) for posts that need it,
  or import a cookies.txt file.
- Light, Dark and System themes. yt-dlp updates itself every few days, or from Settings.

It uses [youtubedl-android](https://github.com/yausername/youtubedl-android), which bundles
yt-dlp with Python, FFmpeg and QuickJS (so YouTube gets its full set of formats).

## Install

The app isn't on Google Play (Play doesn't allow YouTube downloaders), so it's installed from the
APK. On the phone, open the APK and allow your browser or file app to install unknown apps when
Android asks.

Pick the APK for the phone's processor:

| APK | For |
| --- | --- |
| `VideoDownloader-<version>-android-arm64-v8a.apk` | Almost all phones from about 2017 on |
| `VideoDownloader-<version>-android-armeabi-v7a.apk` | Older 32-bit phones |
| `VideoDownloader-<version>-android-x86_64.apk` | Emulators and a few Chromebooks |

## Build

You need JDK 17 and the Android SDK (Android Studio installs both). From this folder:

```bash
./gradlew assembleRelease
```

The APKs land in `app/build/outputs/apk/release/`. `assembleDebug` builds debug APKs signed with
the standard debug key.

### Signing

Release APKs are signed with the key described by
`~/.android/video-downloader/keystore.properties` (or the file `VD_SIGNING_PROPERTIES` points to):

```properties
storeFile=C:/Users/you/.android/video-downloader/release.jks
storePassword=…
keyAlias=videodownloader
keyPassword=…
```

Without that file the release APKs come out unsigned and won't install. Keep the key and its
passwords backed up and out of the repo: Android only installs an update over an existing app
when both are signed with the same key.

## Licenses

The app's own code is MIT, like the rest of this repo. The APK also contains youtubedl-android
(GPLv3), yt-dlp (Unlicense), FFmpeg (GPLv3), Python (PSF) and QuickJS (MIT), so the APK as a
whole is distributed under the GPLv3. Its complete source is this repo plus
[youtubedl-android](https://github.com/yausername/youtubedl-android).
