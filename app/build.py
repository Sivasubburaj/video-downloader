"""Builds the release: release/VideoDownloader-<version>-win64.zip plus SHA256SUMS.txt.

Run from the project folder with uv (it keeps the build tools out of this folder):
    uv run --no-project --python 3.14 --with pyinstaller --with pillow --with imageio-ffmpeg --with sv-ttk app/build.py
"""

import hashlib
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


def make_icon():
    size = 256
    img = Image.new("RGBA", (size, size))
    gradient = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(gradient)
    top, bottom = (255, 82, 82), (124, 58, 237)
    for y in range(size):
        t = y / (size - 1)
        draw.line([(0, y), (size, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    mask = Image.new("L", (size, size))
    ImageDraw.Draw(mask).rounded_rectangle([8, 8, size - 8, size - 8], radius=56, fill=255)
    img.paste(gradient, mask=mask)
    d = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)
    d.rounded_rectangle([110, 48, 146, 140], radius=10, fill=white)       # arrow shaft
    d.polygon([(70, 120), (186, 120), (128, 182)], fill=white)            # arrow head
    d.rounded_rectangle([62, 196, 194, 214], radius=9, fill=white)        # tray
    img.save(ICON, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])


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
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    # LF endings: `sha256sum -c` rejects the CRLF that text mode writes on Windows
    (release / "SHA256SUMS.txt").write_text(f"{digest}  {archive.name}\n", encoding="utf-8", newline="\n")
    shutil.copy2(HERE / "RELEASE-NOTES.md", release / "RELEASE-NOTES.md")
    shots = sorted((ROOT / "screenshots").glob("ui-*.png"))
    if shots:
        (release / "screenshots").mkdir()
        for shot in shots:
            shutil.copy2(shot, release / "screenshots" / shot.name)
    print(f"Done: {app_dir / (NAME + '.exe')}\n      {archive} ({archive.stat().st_size / 1e6:.0f} MB)"
          f"\n      sha256 {digest}")


if __name__ == "__main__":
    main()
