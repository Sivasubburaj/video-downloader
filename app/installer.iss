; Inno Setup 6.7 script for VideoDownloader-<version>-setup.exe.
; app/build.py compiles it, passing AppVersion, SourceDir, OutputDir, IconFile,
; WizardImage and WizardSmallImage with /D.

#define AppName "Video Downloader"
#define AppExe AppName + ".exe"

[Setup]
; Never change AppId: Windows uses it to recognise upgrades and the uninstaller.
AppId={{03291E4E-600A-4B1A-A413-DEC20BC74823}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Sivasubburaj
VersionInfoVersion={#AppVersion}
; Installs just for the current user (in %LOCALAPPDATA%\Programs), so no admin prompt.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=commandline
DefaultDirName={autopf}\{#AppName}
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir={#OutputDir}
OutputBaseFilename=VideoDownloader-{#AppVersion}-setup
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
; Follows Windows' light or dark mode, like the app.
WizardStyle=modern dynamic windows11
WizardImageFile={#WizardImage}
WizardSmallImageFile={#WizardSmallImage}
Compression=lzma2/ultra64
SolidCompression=yes

[Tasks]
Name: desktopicon; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Clear the previous version's runtime so an upgrade doesn't leave stale files behind.
Type: filesandordirs; Name: "{app}\_internal"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; AppUserModelID matches the one the app sets, so the taskbar groups and pins it correctly.
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; AppUserModelID: "VideoDownloader.App"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; AppUserModelID: "VideoDownloader.App"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Settings and the self-updating yt-dlp engine, wherever the app keeps them (DATA_DIR in
; downloader.py). Downloaded videos live elsewhere and are never touched.
; Setup resolves this path at install time, not uninstall time: a test that redirects
; the data folder must set VIDEO_DOWNLOADER_DATA for the install as well.
Type: filesandordirs; Name: "{%VIDEO_DOWNLOADER_DATA|{localappdata}\{#AppName}}"
