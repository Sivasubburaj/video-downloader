VIDEO DOWNLOADER
================

Save YouTube Shorts, Instagram Reels and Facebook videos to your PC as MP4 video
or MP3 audio. It also works with most other video sites.


GETTING STARTED
---------------
With the installer (VideoDownloader-<version>-setup.exe):
1. Double-click the setup file and click through the steps. It installs just
   for you, so it doesn't ask for an administrator password.
2. Open "Video Downloader" from the Start menu.
To remove it: Settings > Apps > Video Downloader > Uninstall.

With the zip (VideoDownloader-<version>-win64.zip):
1. Unzip the folder anywhere you like (for example Documents or Desktop).
   Keep everything inside the "Video Downloader" folder together.
2. Double-click "Video Downloader.exe".
Nothing is installed. To remove the app, delete the folder (and optionally
the settings folder listed below).

Either way, the first time Windows may show "Windows protected your PC".
This appears for apps that aren't code-signed. Click "More info", then
"Run anyway".


HOW TO USE
----------
1. Copy the link of a video (Share > Copy link in the app or browser).
2. Click "Paste". The title and a preview appear.
3. Choose "Video (MP4)" or "Audio only (MP3)", and a quality if you like.
4. Click "Download" (or press Ctrl+Enter).

Files are saved to Downloads\Video Downloader unless you choose another
folder. Double-click a finished download to play it; right-click for more.
You can paste the next link while one is still downloading; it will queue.

Theme: pick System, Light or Dark at the top right. "System" follows your
Windows setting.


INSTAGRAM / FACEBOOK SAY "LOGIN REQUIRED"
-----------------------------------------
Some reels and videos are only visible when you are logged in. Under "Login",
choose a browser where you are signed in to that site and try again.

- Firefox works best.
- Chrome and Edge protect their logins, so they often can't be used.
  If you see a message about this, either use Firefox, or export a
  cookies.txt file with a browser extension (for example
  "Get cookies.txt LOCALLY") and choose "cookies.txt file".
- The app only reads the login to fetch the video. Nothing is uploaded
  anywhere else.


UPDATES
-------
The sites change often, so the download engine (yt-dlp) checks for updates
by itself every few days. If a download suddenly stops working, click
"Check for updates" at the bottom of the window.


ADVANCED: COMMAND LINE
----------------------
"Video Downloader.exe" "<link>"             opens the app with the link ready
"Video Downloader.exe" --download "<link>"  starts downloading it straight away
(using your last-used options)


WHERE THINGS ARE KEPT
---------------------
Settings and the download engine:  %LOCALAPPDATA%\Video Downloader
(paste that into the File Explorer address bar to open it)
Uninstalling removes this folder too. Your downloaded videos are never removed.


PLEASE NOTE
-----------
Only download videos you own, have permission to use, or that are offered
under a licence that allows it (for example Creative Commons). Re-uploading
other people's videos may break copyright law and the sites' terms.

YouTube, Instagram and Facebook are trademarks of their owners. This app is
not affiliated with or endorsed by them.
