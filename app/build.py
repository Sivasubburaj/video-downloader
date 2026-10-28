"""Builds the release: release/VideoDownloader-<version>-win64.zip, the -setup.exe installer
(when Inno Setup 6 is installed) and SHA256SUMS.txt.

Run from the project folder with uv (it keeps the build tools out of this folder):
    uv run --no-project --python 3.14 --with pyinstaller --with pillow --with imageio-ffmpeg --with sv-ttk app/build.py
"""

import hashlib
import os
import re
import shutil
import subprocess
import urllib.request
from pathlib import Path

import imageio_ffmpeg
import PyInstaller.__main__
import sv_ttk  # noqa: F401  (fail early if the theme isn't installed: the exe would be broken)
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
BIN = HERE / "bin"
ICON = HERE / "icon.ico"
NAME = "Video Downloader"
RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
VERSION = re.search(r'APP_VERSION = "([^"]+)"', (HERE / "downloader.py").read_text(encoding="utf-8"))[1]


def fetch_ytdlp():
    """Latest official yt-dlp.exe, checked against the release's published SHA-256 sums."""
    print("Downloading yt-dlp.exe …")
    with urllib.request.urlopen(RELEASE + "yt-dlp.exe") as resp:
        data = resp.read()
    with urllib.request.urlopen(RELEASE + "SHA2-256SUMS") as resp:
        sums = dict(line.split()[::-1] for line in resp.read().decode().splitlines() if line.strip())
    digest = hashlib.sha256(data).hexdigest()
    if sums.get("yt-dlp.exe") != digest:
        raise SystemExit(f"yt-dlp.exe checksum mismatch: {digest} != {sums.get('yt-dlp.exe')}")
    (BIN / "yt-dlp.exe").write_bytes(data)
    print(f"  ok, sha256 {digest[:16]}…")


def copy_ffmpeg():
    src = Path(imageio_ffmpeg.get_ffmpeg_exe())
    print(f"Bundling ffmpeg from {src.name}")
    shutil.copy2(src, BIN / "ffmpeg.exe")


def tool_version(exe, *args):
    out = subprocess.run([str(exe), *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (out.stdout or out.stderr).strip().splitlines()[0]


def gradient(width, height):
    """The icon's red-to-violet background."""
    img = Image.new("RGBA", (width, height))
    draw = ImageDraw.Draw(img)
    top, bottom = (255, 82, 82), (124, 58, 237)
    for y in range(height):
        t = y / (height - 1)
        draw.line([(0, y), (width, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    return img


def glyph(size):
    """The white download arrow over a tray, on a transparent square."""
    img = Image.new("RGBA", (256, 256))
    d = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)
    d.rounded_rectangle([110, 48, 146, 140], radius=10, fill=white)       # arrow shaft
    d.polygon([(70, 120), (186, 120), (128, 182)], fill=white)            # arrow head
    d.rounded_rectangle([62, 196, 194, 214], radius=9, fill=white)        # tray
    return img if size == 256 else img.resize((size, size), Image.LANCZOS)


def icon_image():
    size = 256
    img = Image.new("RGBA", (size, size))
    mask = Image.new("L", (size, size))
    ImageDraw.Draw(mask).rounded_rectangle([8, 8, size - 8, size - 8], radius=56, fill=255)
    img.paste(gradient(size, size), mask=mask)
    return Image.alpha_composite(img, glyph(size))


def make_icon():
    icon_image().save(ICON, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])


def make_wizard_images(work):
    """Installer artwork: a tall panel for the first and last pages, the icon for the rest."""
    width, height = 328, 628  # twice Inno Setup's 164×314, so it stays sharp at high DPI
    panel = gradient(width, height)
    panel.alpha_composite(glyph(200), ((width - 200) // 2, (height - 200) // 2))
    panel.convert("RGB").save(work / "wizard.png")
    icon_image().resize((128, 128), Image.LANCZOS).save(work / "wizard-small.png")


def find_iscc():
    """Inno Setup 6's compiler, from PATH or its usual install folders."""
    if found := shutil.which("iscc"):
        return Path(found)
    env = os.environ
    for base in (env.get("ProgramFiles(x86)"), env.get("ProgramFiles"), env.get("LOCALAPPDATA", "") + r"\Programs"):
        if base and (exe := Path(base) / "Inno Setup 6" / "ISCC.exe").exists():
            return exe
    return None


def make_installer(app_dir, work, release):
    iscc = find_iscc()
    if not iscc:
        print("Inno Setup 6 isn't installed, so no installer was made (https://jrsoftware.org/isdl.php)")
        return None
    print("Building the installer …")
    make_wizard_images(work)
    defines = {
        "AppVersion": VERSION, "SourceDir": app_dir, "OutputDir": release, "IconFile": ICON,
        "WizardImage": work / "wizard.png", "WizardSmallImage": work / "wizard-small.png",
    }
    subprocess.run([str(iscc), "/Q", *(f"/D{k}={v}" for k, v in defines.items()), str(HERE / "installer.iss")],
                   check=True)
    return release / f"VideoDownloader-{VERSION}-setup.exe"


def write_version_file(path):
    nums = tuple(int(n) for n in VERSION.split(".")) + (0,)
    fields = {
        "FileDescription": NAME, "FileVersion": VERSION, "InternalName": NAME,
        "OriginalFilename": f"{NAME}.exe", "ProductName": NAME, "ProductVersion": VERSION,
    }
    strings = ",\n".join(f"        StringStruct('{k}', '{v}')" for k, v in fields.items())
    path.write_text(f"""VSVersionInfo(
  ffi=FixedFileInfo(filevers={nums}, prodvers={nums}, mask=0x3f, flags=0x0, OS=0x40004,
                    fileType=0x1, subtype=0x0, date=(0, 0)),
  kids=[
    StringFileInfo([StringTable('040904B0', [
{strings}])]),
    VarFileInfo([VarStruct('Translation', [1033, 1200])])
  ]
)
""", encoding="utf-8")


def main():
    BIN.mkdir(exist_ok=True)
    fetch_ytdlp()
    copy_ffmpeg()
    make_icon()
    work = ROOT / "build"
    work.mkdir(exist_ok=True)
    version_file = work / "version_info.txt"
    write_version_file(version_file)

    dist = ROOT / "dist"
    PyInstaller.__main__.run([
        str(HERE / "downloader.py"),
        "--noconfirm", "--clean", "--windowed", "--onedir",
        "--name", NAME,
        "--icon", str(ICON),
        "--version-file", str(version_file),
        "--collect-data", "sv_ttk",
        "--add-data", f"{BIN / 'yt-dlp.exe'};bin",
        "--add-data", f"{BIN / 'ffmpeg.exe'};bin",
        "--add-data", f"{ICON};.",
        "--distpath", str(dist),
        "--workpath", str(work),
        "--specpath", str(work),
    ])

    app_dir = dist / NAME
    notices = (HERE / "THIRD-PARTY-NOTICES.txt").read_text(encoding="utf-8").format(
        ytdlp=tool_version(BIN / "yt-dlp.exe", "--version"),
        ffmpeg=tool_version(BIN / "ffmpeg.exe", "-version"),
    )
    (app_dir / "THIRD-PARTY-NOTICES.txt").write_text(notices, encoding="utf-8")
    shutil.copy2(HERE / "README.txt", app_dir / "README.txt")

    release = ROOT / "release"
    shutil.rmtree(release, ignore_errors=True)
    release.mkdir()
    print("Zipping …")
    archive = Path(shutil.make_archive(str(release / f"VideoDownloader-{VERSION}-win64"), "zip",
                                       root_dir=dist, base_dir=NAME))
    installer = make_installer(app_dir, work, release)
    files = [archive] + ([installer] if installer else [])
    sums = "".join(f"{hashlib.sha256(f.read_bytes()).hexdigest()}  {f.name}\n" for f in files)
    # LF endings: `sha256sum -c` rejects the CRLF that text mode writes on Windows
    (release / "SHA256SUMS.txt").write_text(sums, encoding="utf-8", newline="\n")
    shutil.copy2(HERE / "RELEASE-NOTES.md", release / "RELEASE-NOTES.md")
    shots = sorted((ROOT / "screenshots").glob("ui-*.png"))
    if shots:
        (release / "screenshots").mkdir()
        for shot in shots:
            shutil.copy2(shot, release / "screenshots" / shot.name)
    print(f"Done: {app_dir / (NAME + '.exe')}")
    for f in files:
        print(f"      {f} ({f.stat().st_size / 1e6:.0f} MB)")
    print(sums, end="")


if __name__ == "__main__":
    main()
