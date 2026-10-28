"""Video Downloader - a small Windows app for YouTube Shorts, Instagram Reels and Facebook videos.

The window is tkinter with the Sun Valley theme (Windows 11 look, light and dark). The actual
downloading is done by the official yt-dlp.exe, which lives in %LOCALAPPDATA%\\Video Downloader so
it can update itself, and ffmpeg.exe, which is bundled and merges video+audio / converts to MP3.
"""

import contextlib
import ctypes
import io
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import tkinter as tk
import tkinter.font as tkfont
import urllib.request
import uuid
import winreg
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

import sv_ttk
from PIL import Image, ImageDraw, ImageTk

APP_NAME = "Video Downloader"
APP_VERSION = "1.0.0"
YTDLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
AUTO_UPDATE_DAYS = 3
NO_WINDOW = 0x08000000  # CREATE_NO_WINDOW: keep yt-dlp/ffmpeg consoles hidden

QUALITIES = {
    "Best available": "res",
    "1080p": "res:1080",
    "720p": "res:720",
    "480p": "res:480",
    "360p": "res:360",
}
COOKIE_SOURCES = ["None", "Firefox", "Chrome", "Edge", "Brave", "Opera", "cookies.txt file"]
SITE_NAMES = {"Youtube": "YouTube", "YoutubeTab": "YouTube", "FacebookReel": "Facebook",
              "InstagramStory": "Instagram", "TikTok": "TikTok", "Twitter": "X"}
NETWORK_ERRORS = ("getaddrinfo", "timed out", "connection refused", "connection reset",
                  "network is unreachable", "failed to resolve", "no route to host")
THEMES = ["System", "Light", "Dark"]
# Sun Valley wants Windows 11's "Segoe UI Variable"; on Windows 10 Tk would fall back to Arial
FONT_FALLBACKS = {
    "SunValleyCaptionFont": "Segoe UI", "SunValleyBodyFont": "Segoe UI", "SunValleyBodyLargeFont": "Segoe UI",
    "SunValleyBodyStrongFont": "Segoe UI Semibold", "SunValleySubtitleFont": "Segoe UI Semibold",
    "SunValleyTitleFont": "Segoe UI Semibold", "SunValleyTitleLargeFont": "Segoe UI Semibold",
    "SunValleyDisplayFont": "Segoe UI Semibold",
}
FINISHED = ("Done", "Failed", "Cancelled")

PALETTE = {
    "light": {"muted": "#5d5d5d", "error": "#c42b1c", "ok": "#0f7b0f", "tile": "#e6e6e6",
              "glyph": "#b8b8b8", "menu_bg": "#f9f9f9", "menu_fg": "#1a1a1a", "menu_hi": "#e0e0e0"},
    "dark": {"muted": "#a3a3a3", "error": "#ff99a4", "ok": "#6ccb5f", "tile": "#2f2f2f",
             "glyph": "#5a5a5a", "menu_bg": "#2b2b2b", "menu_fg": "#f3f3f3", "menu_hi": "#3d3d3d"},
}

BUNDLE_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
BUNDLE_BIN = BUNDLE_DIR / "bin"
DATA_DIR = Path(os.environ.get("VIDEO_DOWNLOADER_DATA")
                or Path(os.environ.get("LOCALAPPDATA", Path.home())) / APP_NAME)
YTDLP = DATA_DIR / "yt-dlp.exe"
TEMP_ROOT = DATA_DIR / "temp"
SETTINGS_FILE = DATA_DIR / "settings.json"


def downloads_folder():
    """The user's real Downloads folder (it can be moved), falling back to ~/Downloads."""
    try:
        guid = (ctypes.c_byte * 16)(*uuid.UUID("374DE290-123F-4565-9164-39C4925E467B").bytes_le)
        path = ctypes.c_wchar_p()
        if ctypes.windll.shell32.SHGetKnownFolderPath(guid, 0, None, ctypes.byref(path)) == 0:
            result = path.value
            ctypes.windll.ole32.CoTaskMemFree(path)
            return Path(result)
    except Exception:
        pass
    return Path.home() / "Downloads"


def system_theme():
    try:
        key = r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key) as k:
            return "light" if winreg.QueryValueEx(k, "AppsUseLightTheme")[0] else "dark"
    except OSError:
        return "light"


def set_title_bar(root, dark):
    """Dark or light window title bar to match the theme (Windows 10 2004+ and Windows 11)."""
    try:
        hwnd = ctypes.windll.user32.GetParent(root.winfo_id())
        value = ctypes.c_int(1 if dark else 0)
        for attribute in (20, 19):  # DWMWA_USE_IMMERSIVE_DARK_MODE, then its pre-2004 number
            if ctypes.windll.dwmapi.DwmSetWindowAttribute(
                    hwnd, attribute, ctypes.byref(value), ctypes.sizeof(value)) == 0:
                break
        # SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED: repaint the frame
        ctypes.windll.user32.SetWindowPos(hwnd, None, 0, 0, 0, 0, 0x01 | 0x02 | 0x04 | 0x10 | 0x20)
    except Exception:
        pass


def ffmpeg_path():
    bundled = BUNDLE_BIN / "ffmpeg.exe"
    if bundled.exists():
        return bundled
    found = shutil.which("ffmpeg")
    return Path(found) if found else None


def js_runtime_args():
    """YouTube works best when yt-dlp has a JavaScript runtime; use one if installed."""
    for name in ("deno", "node", "bun"):
        found = shutil.which(name)
        if found:
            return ["--js-runtimes", f"{name}:{found}"]
    return []


def run_hidden(args, **kwargs):
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    return subprocess.Popen(
        args,
        creationflags=NO_WINDOW,
        stdin=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
        **kwargs,
    )


def kill_tree(proc):
    if proc and proc.poll() is None:
        subprocess.run(
            ["taskkill", "/PID", str(proc.pid), "/T", "/F"],
            creationflags=NO_WINDOW,
            capture_output=True,
        )


def format_bytes(n):
    if not n:
        return ""
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{n:.0f} B"
        n /= 1024


def format_duration(seconds):
    if not seconds:
        return ""
    seconds = int(round(seconds))
    h, rest = divmod(seconds, 3600)
    m, s = divmod(rest, 60)
    return f"{h}:{m:02}:{s:02}" if h else f"{m}:{s:02}"


def friendly_error(text):
    """Turn yt-dlp's error output into something a non-technical person can act on."""
    errors = [line for line in text.splitlines() if line.startswith("ERROR:")]
    low = ("\n".join(errors) or text).lower()
    if "could not copy" in low and "cookie database" in low:
        return "Couldn't read your browser's login. Close that browser completely and try again."
    if "dpapi" in low or "app-bound" in low or "failed to decrypt" in low:
        return ("This browser's login can't be read (Chrome/Edge lock it). "
                "Use Firefox or a cookies.txt file under 'Login'.")
    if "private" in low and ("video" in low or "account" in low):
        return "This video is private. Choose a browser under 'Login' where you can see it."
    if "login" in low or "log in" in low or "sign in" in low or "cookies" in low:
        return ("This video needs a login. Under 'Login', pick a browser where you're signed in "
                "to the site, then try again.")
    if "unsupported url" in low:
        return "That link isn't supported. Copy the link of the video itself and try again."
    if "http error 404" in low:
        return "Nothing was found at this link. Check that it's the link of the video itself."
    if "not available" in low or "removed" in low or "unavailable" in low:
        return "This video isn't available (it may be deleted, private, or blocked in your country)."
    if any(sign in low for sign in NETWORK_ERRORS):
        return "Couldn't connect. Check your internet connection and try again."
    msg = (errors[-1] if errors else text.strip().splitlines()[-1] if text.strip() else "Unknown error")
    return msg.replace("ERROR: ", "", 1)[:300]


def rounded(img, radius):
    """Copy of img with rounded, transparent corners (drawn at 4x for smooth edges)."""
    big = Image.new("L", (img.width * 4, img.height * 4), 0)
    ImageDraw.Draw(big).rounded_rectangle([0, 0, big.width - 1, big.height - 1], radius=radius * 4, fill=255)
    out = img.convert("RGBA")
    out.putalpha(big.resize(img.size, Image.LANCZOS))
    return out


def placeholder(width, height, radius, tile, glyph):
    """Empty preview tile with a play symbol."""
    s = 4
    img = Image.new("RGBA", (width * s, height * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=radius * s, fill=tile)
    cx, cy, r = img.width / 2, img.height / 2, min(width, height) * s * 0.16
    d.polygon([(cx - r * 0.7, cy - r), (cx - r * 0.7, cy + r), (cx + r, cy)], fill=glyph)
    return img.resize((width, height), Image.LANCZOS)


class EngineGate:
    """Any number of yt-dlp runs can happen at once, but an update waits for them (and blocks new ones)."""

    def __init__(self):
        self._cond = threading.Condition()
        self._users = 0
        self._exclusive = False

    @contextlib.contextmanager
    def use(self):
        with self._cond:
            while self._exclusive:
                self._cond.wait()
            self._users += 1
        try:
            yield
        finally:
            with self._cond:
                self._users -= 1
                self._cond.notify_all()

    @contextlib.contextmanager
    def exclusive(self):
        with self._cond:
            while self._exclusive or self._users:
                self._cond.wait()
            self._exclusive = True
        try:
            yield
        finally:
            with self._cond:
                self._exclusive = False
                self._cond.notify_all()


class Job:
    def __init__(self, url, options, title=None):
        self.id = uuid.uuid4().hex[:12]
        self.url = url
        self.options = options
        self.title = title or url
        self.status = "Queued"
        self.filepath = None
        self.proc = None
        self.cancelled = False


class App:
    def __init__(self, root, initial_url=None, auto_download=False):
        self.root = root
        self.ui_queue = queue.Queue()
        self.jobs = {}
        self.job_queue = queue.Queue()
        self.engine = EngineGate()
        self.engine_ready = threading.Event()
        self.scale = root.winfo_fpixels("1i") / 96  # Windows display scaling (1.0 = 100%)
        self.info = None
        self.info_url = None
        self.fetch_token = 0
        self.thumb_source = None  # PIL image of the current preview, None for the placeholder
        self.thumb_image = None
        self.applied_theme = None
        self.base_font_sizes = {}
        self.settings = self.load_settings()

        DATA_DIR.mkdir(parents=True, exist_ok=True)
        root.withdraw()  # build and theme everything before the window first appears
        # fonts must be fixed before any widget uses them: Tk won't re-resolve them afterwards
        sv_ttk.set_theme("light")
        self.tune_fonts()
        self.build_ui()
        self.apply_theme()
        self.place_window()
        root.deiconify()

        threading.Thread(target=self.download_worker, daemon=True).start()
        threading.Thread(target=self.prepare_engine, daemon=True).start()
        self.root.after(100, self.process_ui_queue)
        self.root.after(3000, self.watch_system_theme)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        if initial_url:
            self.url_var.set(initial_url)
            self.root.after(200, self.start_download if auto_download else self.fetch_info)

    # ---------- settings ----------

    def load_settings(self):
        defaults = {
            "folder": str(downloads_folder() / APP_NAME),
            "mode": "video",
            "quality": "Best available",
            "cookies": "None",
            "cookies_file": "",
            "theme": "System",
            "last_update_check": 0,
        }
        try:
            defaults.update(json.loads(SETTINGS_FILE.read_text(encoding="utf-8")))
        except Exception:
            pass
        return defaults

    def save_settings(self):
        self.settings.update(
            folder=self.folder_var.get(),
            mode=self.mode_var.get(),
            quality=self.quality_var.get(),
            cookies=self.cookies_var.get(),
            theme=self.theme_var.get(),
        )
        try:
            SETTINGS_FILE.write_text(json.dumps(self.settings, indent=2), encoding="utf-8")
        except OSError:
            pass

    # ---------- layout ----------

    def px(self, n):
        return int(n * self.scale)

    def place_window(self):
        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        w, h = min(self.px(780), sw - 40), min(self.px(860), sh - 90)
        self.root.minsize(min(self.px(660), w), min(self.px(620), h))
        self.root.geometry(f"{w}x{h}+{(sw - w) // 2}+{max(0, (sh - h) // 2 - 20)}")

    def build_ui(self):
        self.root.title(APP_NAME)
        icon = BUNDLE_DIR / "icon.ico"
        if icon.exists():
            with contextlib.suppress(tk.TclError):
                self.root.iconbitmap(default=str(icon))
        gap = self.px(12)

        outer = ttk.Frame(self.root, padding=(self.px(20), self.px(16)))
        outer.pack(fill="both", expand=True)
        outer.columnconfigure(0, weight=1)

        # header
        header = ttk.Frame(outer)
        header.grid(row=0, column=0, sticky="ew", pady=(0, gap))
        header.columnconfigure(0, weight=1)
        ttk.Label(header, text=APP_NAME, font="SunValleySubtitleFont").grid(row=0, column=0, sticky="w")
        ttk.Label(header, text="YouTube Shorts  ·  Instagram Reels  ·  Facebook videos",
                  style="Muted.TLabel").grid(row=1, column=0, sticky="w")
        theme_box = ttk.Frame(header)
        theme_box.grid(row=0, column=1, rowspan=2, sticky="e")
        ttk.Label(theme_box, text="Theme", style="Muted.TLabel").pack(side="left", padx=(0, self.px(8)))
        self.theme_var = tk.StringVar(value=self.settings["theme"] if self.settings["theme"] in THEMES
                                      else "System")
        theme_menu = ttk.Combobox(theme_box, textvariable=self.theme_var, values=THEMES,
                                  state="readonly", width=8)
        theme_menu.pack(side="left")
        theme_menu.bind("<<ComboboxSelected>>", lambda e: (self.apply_theme(), self.save_settings()))

        # link + preview card
        card = ttk.Frame(outer, style="Card.TFrame", padding=self.px(16))
        card.grid(row=1, column=0, sticky="ew")
        card.columnconfigure(0, weight=1)
        ttk.Label(card, text="Video link", font="SunValleyBodyStrongFont").grid(row=0, column=0, sticky="w")
        link_row = ttk.Frame(card)
        link_row.grid(row=1, column=0, sticky="ew", pady=(self.px(6), gap))
        link_row.columnconfigure(0, weight=1)
        self.url_var = tk.StringVar()
        self.url_entry = ttk.Entry(link_row, textvariable=self.url_var)
        self.url_entry.grid(row=0, column=0, sticky="ew")
        self.url_entry.bind("<Return>", lambda e: self.fetch_info())
        self.url_entry.bind("<<Paste>>", lambda e: self.root.after(50, self.fetch_info))
        ttk.Button(link_row, text="Paste", command=self.paste_link).grid(row=0, column=1, padx=(self.px(8), 0))
        ttk.Button(link_row, text="Clear", command=self.clear_link).grid(row=0, column=2, padx=(self.px(6), 0))

        preview = ttk.Frame(card)
        preview.grid(row=2, column=0, sticky="ew")
        preview.columnconfigure(1, weight=1)
        self.thumb_w, self.thumb_h = self.px(176), self.px(132)
        thumb_box = ttk.Frame(preview, width=self.thumb_w, height=self.thumb_h)
        thumb_box.grid(row=0, column=0, sticky="nw")
        thumb_box.grid_propagate(False)
        self.thumb_label = ttk.Label(thumb_box)
        self.thumb_label.place(relx=0.5, rely=0.5, anchor="center")
        text_box = ttk.Frame(preview)
        text_box.grid(row=0, column=1, sticky="nw", padx=(self.px(16), 0))
        self.title_var = tk.StringVar(value="Paste a link to see the video here")
        self.meta_var = tk.StringVar(value="Works with YouTube, Instagram, Facebook and many other sites.")
        ttk.Label(text_box, textvariable=self.title_var, font="SunValleyBodyLargeFont",
                  wraplength=self.px(470), justify="left").pack(anchor="w")
        ttk.Label(text_box, textvariable=self.meta_var, style="Muted.TLabel",
                  wraplength=self.px(470), justify="left").pack(anchor="w", pady=(self.px(6), 0))

        # options card
        opts = ttk.Frame(outer, style="Card.TFrame", padding=self.px(16))
        opts.grid(row=2, column=0, sticky="ew", pady=gap)
        opts.columnconfigure(1, weight=1)
        row_pad = (0, self.px(10))
        label_pad = (0, self.px(16))

        ttk.Label(opts, text="Save as").grid(row=0, column=0, sticky="w", padx=label_pad, pady=row_pad)
        mode_row = ttk.Frame(opts)
        mode_row.grid(row=0, column=1, sticky="w", pady=row_pad)
        self.mode_var = tk.StringVar(value=self.settings["mode"])
        ttk.Radiobutton(mode_row, text="Video (MP4)", value="video", variable=self.mode_var,
                        command=self.on_mode_change).pack(side="left")
        ttk.Radiobutton(mode_row, text="Audio only (MP3)", value="audio", variable=self.mode_var,
                        command=self.on_mode_change).pack(side="left", padx=(self.px(20), 0))

        ttk.Label(opts, text="Quality").grid(row=1, column=0, sticky="w", padx=label_pad, pady=row_pad)
        self.quality_var = tk.StringVar(value=self.settings["quality"])
        self.quality_box = ttk.Combobox(opts, textvariable=self.quality_var, values=list(QUALITIES),
                                        state="readonly", width=16)
        self.quality_box.grid(row=1, column=1, sticky="w", pady=row_pad)

        ttk.Label(opts, text="Save to").grid(row=2, column=0, sticky="w", padx=label_pad, pady=row_pad)
        folder_row = ttk.Frame(opts)
        folder_row.grid(row=2, column=1, sticky="ew", pady=row_pad)
        folder_row.columnconfigure(0, weight=1)
        self.folder_var = tk.StringVar(value=self.settings["folder"])
        ttk.Entry(folder_row, textvariable=self.folder_var).grid(row=0, column=0, sticky="ew")
        ttk.Button(folder_row, text="Browse…", command=self.choose_folder).grid(
            row=0, column=1, padx=(self.px(8), 0))
        ttk.Button(folder_row, text="Open", command=self.open_folder).grid(row=0, column=2, padx=(self.px(6), 0))

        ttk.Label(opts, text="Login").grid(row=3, column=0, sticky="w", padx=label_pad)
        login_row = ttk.Frame(opts)
        login_row.grid(row=3, column=1, sticky="w")
        self.cookies_var = tk.StringVar(value=self.settings["cookies"])
        cookies_box = ttk.Combobox(login_row, textvariable=self.cookies_var, values=COOKIE_SOURCES,
                                   state="readonly", width=16)
        cookies_box.pack(side="left")
        cookies_box.bind("<<ComboboxSelected>>", self.on_cookies_change)
        ttk.Label(login_row, text="Only if Instagram or Facebook asks you to log in",
                  style="Muted.TLabel").pack(side="left", padx=(self.px(12), 0))
        self.on_mode_change()

        # download button + progress
        action_row = ttk.Frame(outer)
        action_row.grid(row=3, column=0, sticky="ew")
        action_row.columnconfigure(1, weight=1)
        self.download_btn = ttk.Button(action_row, text="Download", style="Big.Accent.TButton",
                                       command=self.start_download)
        self.download_btn.grid(row=0, column=0, rowspan=2, sticky="w")
        self.progress = ttk.Progressbar(action_row, mode="determinate", maximum=100)
        self.progress.grid(row=0, column=1, sticky="ew", padx=(self.px(16), 0), pady=(self.px(4), 0))
        self.status_var = tk.StringVar(value="Starting…")
        self.status_label = ttk.Label(action_row, textvariable=self.status_var, style="Muted.TLabel")
        self.status_label.grid(row=1, column=1, sticky="w", padx=(self.px(16), 0), pady=(self.px(6), 0))
        self.root.bind("<Control-Return>", lambda e: self.start_download())

        # downloads card
        list_card = ttk.Frame(outer, style="Card.TFrame", padding=self.px(16))
        list_card.grid(row=4, column=0, sticky="nsew", pady=(gap, 0))
        outer.rowconfigure(4, weight=1)
        list_card.columnconfigure(0, weight=1)
        list_card.rowconfigure(1, weight=1)
        list_head = ttk.Frame(list_card)
        list_head.grid(row=0, column=0, columnspan=2, sticky="ew", pady=(0, self.px(8)))
        list_head.columnconfigure(1, weight=1)
        ttk.Label(list_head, text="Downloads", font="SunValleyBodyStrongFont").grid(row=0, column=0, sticky="w")
        ttk.Label(list_head, text="Double-click to play  ·  right-click for more",
                  style="Muted.TLabel").grid(row=0, column=1, sticky="w", padx=(self.px(12), 0))
        ttk.Button(list_head, text="Cancel", command=self.cancel_selected).grid(row=0, column=2)
        ttk.Button(list_head, text="Clear finished", command=self.clear_finished).grid(
            row=0, column=3, padx=(self.px(6), 0))

        self.tree = ttk.Treeview(list_card, columns=("title", "status"), show="headings", height=4)
        self.tree.heading("title", text="Video", anchor="w")
        self.tree.heading("status", text="Status", anchor="w")
        self.tree.column("title", width=self.px(430))
        self.tree.column("status", width=self.px(170), anchor="w", stretch=False)
        self.tree.grid(row=1, column=0, sticky="nsew")
        scroll = ttk.Scrollbar(list_card, orient="vertical", command=self.tree.yview)
        scroll.grid(row=1, column=1, sticky="ns", padx=(self.px(4), 0))
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.bind("<Double-1>", lambda e: self.open_selected())
        self.tree.bind("<Button-3>", self.show_menu)

        self.menu = tk.Menu(self.root, tearoff=0)
        self.menu.add_command(label="Play", command=self.open_selected)
        self.menu.add_command(label="Show in folder", command=self.show_selected_in_folder)
        self.menu.add_command(label="Copy link", command=self.copy_selected_link)
        self.menu.add_separator()
        self.menu.add_command(label="Cancel", command=self.cancel_selected)
        self.menu.add_command(label="Remove from list", command=self.remove_selected)

        # footer
        footer = ttk.Frame(outer)
        footer.grid(row=5, column=0, sticky="ew", pady=(gap, 0))
        footer.columnconfigure(0, weight=1)
        self.engine_var = tk.StringVar(value="Engine: checking…")
        ttk.Label(footer, textvariable=self.engine_var, style="Muted.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(footer, text=f"v{APP_VERSION}", style="Muted.TLabel").grid(
            row=0, column=1, padx=(0, self.px(12)))
        self.update_btn = ttk.Button(footer, text="Check for updates", command=self.update_engine)
        self.update_btn.grid(row=0, column=2)

        self.url_entry.focus_set()

    # ---------- theme ----------

    def apply_theme(self):
        choice = self.theme_var.get()
        mode = system_theme() if choice == "System" else choice.lower()
        if mode == self.applied_theme:
            return
        self.applied_theme = mode
        sv_ttk.set_theme(mode)
        self.tune_fonts()
        pal = PALETTE[mode]

        # custom styles belong to the active ttk theme, so they're set again after every switch
        style = ttk.Style()
        style.configure("Muted.TLabel", foreground=pal["muted"])
        style.configure("Error.TLabel", foreground=pal["error"])
        style.configure("Ok.TLabel", foreground=pal["ok"])
        style.configure("Big.Accent.TButton", font="SunValleyBodyStrongFont",
                        padding=(self.px(28), self.px(10)))
        style.configure("Treeview", rowheight=self.px(30))
        self.tree.tag_configure("Done", foreground=pal["ok"])
        self.tree.tag_configure("Failed", foreground=pal["error"])
        self.tree.tag_configure("Cancelled", foreground=pal["muted"])
        self.placeholder_image = ImageTk.PhotoImage(
            placeholder(self.thumb_w, self.thumb_h, self.px(10), pal["tile"], pal["glyph"]))
        if self.thumb_source is None:
            self.thumb_label.configure(image=self.placeholder_image)

        self.root.update_idletasks()
        set_title_bar(self.root, mode == "dark")
        # sv-ttk runs tk_setPalette once <<ThemeChanged>> arrives; undo its side effects after that
        self.root.after(30, self.after_theme_change)

    def after_theme_change(self):
        """tk_setPalette pins colors on every widget, which would hide our label styles."""
        pal = PALETTE[self.applied_theme]
        stack = [self.root]
        while stack:
            widget = stack.pop()
            stack.extend(widget.winfo_children())
            if widget.winfo_class() == "TLabel":
                widget.configure(foreground="", background="")
        self.menu.configure(bg=pal["menu_bg"], fg=pal["menu_fg"], activebackground=pal["menu_hi"],
                            activeforeground=pal["menu_fg"], relief="flat", borderwidth=1)

    def tune_fonts(self):
        """Sun Valley fonts are sized in pixels: grow them with display scaling, and swap in Segoe UI
        where Windows 11's Segoe UI Variable isn't installed."""
        if not hasattr(self, "has_variable_font"):
            self.has_variable_font = any(f.startswith("Segoe UI Variable") for f in tkfont.families())
        for name in tkfont.names():
            if name.startswith("SunValley"):
                font = tkfont.nametofont(name)
                base = self.base_font_sizes.setdefault(name, font.cget("size"))
                options = {"size": round(base * self.scale) if base < 0 else base}
                if not self.has_variable_font and name in FONT_FALLBACKS:
                    options["family"] = FONT_FALLBACKS[name]
                font.configure(**options)

    def watch_system_theme(self):
        if self.theme_var.get() == "System":
            self.apply_theme()
        self.root.after(3000, self.watch_system_theme)

    # ---------- small UI handlers ----------

    def post(self, fn, *args):
        """Run fn(*args) on the UI thread."""
        self.ui_queue.put((fn, args))

    def process_ui_queue(self):
        try:
            while True:
                fn, args = self.ui_queue.get_nowait()
                fn(*args)
        except queue.Empty:
            pass
        self.root.after(100, self.process_ui_queue)

    def on_mode_change(self):
        self.quality_box.configure(state="disabled" if self.mode_var.get() == "audio" else "readonly")

    def on_cookies_change(self, _event=None):
        if self.cookies_var.get() == "cookies.txt file":
            path = filedialog.askopenfilename(
                title="Choose your cookies.txt file",
                filetypes=[("Cookies file", "*.txt"), ("All files", "*.*")],
                initialfile=self.settings.get("cookies_file") or "",
            )
            if path:
                self.settings["cookies_file"] = path
            elif not self.settings.get("cookies_file"):
                self.cookies_var.set("None")
        self.save_settings()
        if self.url_var.get().strip() and not self.info:
            self.fetch_info()  # the link may have failed only because it needed a login

    def paste_link(self):
        try:
            text = self.root.clipboard_get().strip()
        except tk.TclError:
            text = ""
        if text:
            self.url_var.set(text)
            self.fetch_info()

    def clear_link(self):
        self.url_var.set("")
        self.fetch_token += 1
        self.show_info(None)
        self.url_entry.focus_set()

    def choose_folder(self):
        folder = filedialog.askdirectory(initialdir=self.folder_var.get() or str(downloads_folder()))
        if folder:
            self.folder_var.set(os.path.normpath(folder))
            self.save_settings()

    def open_folder(self):
        folder = Path(self.folder_var.get())
        folder.mkdir(parents=True, exist_ok=True)
        os.startfile(folder)

    def selected_job(self):
        sel = self.tree.selection()
        return self.jobs.get(sel[0]) if sel else None

    def open_selected(self):
        job = self.selected_job()
        if job and job.filepath and Path(job.filepath).exists():
            os.startfile(job.filepath)

    def show_selected_in_folder(self):
        job = self.selected_job()
        if job and job.filepath and Path(job.filepath).exists():
            subprocess.Popen(["explorer", "/select,", job.filepath])
        else:
            self.open_folder()

    def copy_selected_link(self):
        job = self.selected_job()
        if job:
            self.root.clipboard_clear()
            self.root.clipboard_append(job.url)

    def show_menu(self, event):
        row = self.tree.identify_row(event.y)
        if row:
            self.tree.selection_set(row)
            self.menu.tk_popup(event.x_root, event.y_root)

    def cancel_selected(self):
        job = self.selected_job()
        if job is None:  # nothing selected: cancel whatever is downloading
            job = next((j for j in self.jobs.values() if j.proc and j.proc.poll() is None), None)
        if job and job.status not in FINISHED:
            job.cancelled = True
            kill_tree(job.proc)
            self.set_job_status(job, "Cancelled")

    def remove_selected(self):
        job = self.selected_job()
        if job:
            if job.status not in FINISHED:
                self.cancel_selected()
            self.tree.delete(job.id)
            del self.jobs[job.id]

    def clear_finished(self):
        for job in list(self.jobs.values()):
            if job.status in FINISHED:
                self.tree.delete(job.id)
                del self.jobs[job.id]

    def set_job_status(self, job, status, title=None):
        job.status = status
        if title:
            job.title = title
        if self.tree.exists(job.id):
            self.tree.item(job.id, values=(job.title, status), tags=(status,))

    def set_status(self, text, kind="Muted"):
        self.status_var.set(text if len(text) <= 100 else text[:99] + "…")
        self.status_label.configure(style=f"{kind}.TLabel")

    # ---------- engine (yt-dlp) setup and updates ----------

    def common_args(self, options):
        args = [str(YTDLP), "--no-colors", "--encoding", "utf-8"]
        ff = ffmpeg_path()
        if ff:
            args += ["--ffmpeg-location", str(ff)]
        args += js_runtime_args()
        source = options["cookies"]
        if source == "cookies.txt file" and options.get("cookies_file"):
            args += ["--cookies", options["cookies_file"]]
        elif source not in ("None", "cookies.txt file"):
            args += ["--cookies-from-browser", source.lower()]
        return args

    def prepare_engine(self):
        """Make sure yt-dlp.exe is in the data folder (where it can update itself)."""
        with self.engine.exclusive():
            try:
                if not YTDLP.exists():
                    bundled = BUNDLE_BIN / "yt-dlp.exe"
                    if bundled.exists():
                        shutil.copy2(bundled, YTDLP)
                    else:
                        self.post(self.set_status, "Downloading the download engine (first run)…")
                        tmp = YTDLP.with_suffix(".download")
                        urllib.request.urlretrieve(YTDLP_URL, tmp)
                        tmp.replace(YTDLP)
                    self.settings["last_update_check"] = time.time()  # it's brand new
                    self.post(self.save_settings)
                version = self.engine_version()
                self.post(self.engine_var.set, f"Engine: yt-dlp {version}")
                self.post(self.set_status, "Ready. Paste a link to get started.")
            except Exception as exc:
                self.post(self.engine_var.set, "Engine: missing")
                self.post(self.set_status, f"Couldn't set up the download engine: {exc}", "Error")
                return
            finally:
                self.engine_ready.set()
        if time.time() - self.settings.get("last_update_check", 0) > AUTO_UPDATE_DAYS * 86400:
            self.run_update(quiet=True)

    def engine_version(self):
        proc = run_hidden([str(YTDLP), "--version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, _ = proc.communicate(timeout=60)
        return out.strip() or "unknown"

    def update_engine(self):
        self.update_btn.configure(state="disabled")
        threading.Thread(target=self.run_update, daemon=True).start()

    def run_update(self, quiet=False):
        self.post(self.engine_var.set, "Engine: checking for updates…")
        with self.engine.exclusive():
            try:
                proc = run_hidden([str(YTDLP), "-U"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                out, _ = proc.communicate(timeout=300)
                version = self.engine_version()
                self.settings["last_update_check"] = time.time()
                self.post(self.save_settings)
                updated = "updated yt-dlp" in out.lower()
                self.post(self.engine_var.set, f"Engine: yt-dlp {version}  ·  "
                          + ("just updated" if updated else "up to date"))
                if not quiet and not updated and "up to date" not in out.lower():
                    self.post(messagebox.showwarning, APP_NAME,
                              "Couldn't check for updates:\n\n" + out.strip()[-500:])
            except Exception as exc:
                self.post(self.engine_var.set, "Engine: update check failed")
                if not quiet:
                    self.post(messagebox.showwarning, APP_NAME, f"Couldn't check for updates:\n\n{exc}")
        self.post(self.update_btn.configure, {"state": "normal"})

    # ---------- preview ----------

    def current_options(self):
        return {
            "mode": self.mode_var.get(),
            "quality": self.quality_var.get(),
            "folder": self.folder_var.get(),
            "cookies": self.cookies_var.get(),
            "cookies_file": self.settings.get("cookies_file", ""),
        }

    def fetch_info(self):
        url = self.url_var.get().strip()
        if not re.match(r"https?://", url):
            if url:
                self.title_var.set("That doesn't look like a link")
                self.meta_var.set("Video links start with https://")
            return
        if url == self.info_url and self.info:
            return
        self.fetch_token += 1
        token = self.fetch_token
        self.info, self.info_url = None, None
        self.title_var.set("Looking up the video…")
        self.meta_var.set("")
        self.set_thumbnail(None)
        options = self.current_options()
        threading.Thread(target=self.fetch_worker, args=(url, options, token), daemon=True).start()

    def fetch_worker(self, url, options, token):
        self.engine_ready.wait(120)
        with self.engine.use():
            if token != self.fetch_token:
                return
            args = self.common_args(options) + ["-J", "--no-playlist", "--no-warnings", url]
            try:
                proc = run_hidden(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                out, err = proc.communicate(timeout=120)
            except Exception as exc:
                out, err = "", str(exc)
        if token != self.fetch_token:
            return
        try:
            info = json.loads(out)
        except ValueError:
            info = None
        if not isinstance(info, dict):  # yt-dlp prints "null" when it found nothing
            self.post(self.show_fetch_error, friendly_error(err or out), token)
            return
        thumb = None
        if info.get("thumbnail"):
            try:
                req = urllib.request.Request(info["thumbnail"], headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=15) as resp:
                    img = Image.open(io.BytesIO(resp.read())).convert("RGB")
                    img.thumbnail((self.thumb_w, self.thumb_h), Image.LANCZOS)
                    thumb = rounded(img, self.px(10))
            except Exception:
                pass
        self.post(self.show_info, info, url, thumb, token)

    def show_fetch_error(self, message, token):
        if token != self.fetch_token:
            return
        self.title_var.set("Couldn't load this link")
        self.meta_var.set(message)
        self.set_thumbnail(None)

    def show_info(self, info, url=None, thumb=None, token=None):
        if token is not None and token != self.fetch_token:
            return
        if not info:
            self.info = self.info_url = None
            self.title_var.set("Paste a link to see the video here")
            self.meta_var.set("Works with YouTube, Instagram, Facebook and many other sites.")
            self.set_thumbnail(None)
            return
        self.info, self.info_url = info, url
        title = info.get("title") or info.get("id") or "Untitled"
        if info.get("_type") == "playlist":
            count = len(info.get("entries") or [])
            title = f"{title} ({count} videos)"
        self.title_var.set(title)
        site = info.get("extractor_key", "")
        bits = [SITE_NAMES.get(site, site), info.get("uploader") or info.get("channel") or "",
                format_duration(info.get("duration"))]
        size = info.get("filesize") or info.get("filesize_approx")
        if size:
            bits.append(f"~{format_bytes(size)}")
        self.meta_var.set("  ·  ".join(b for b in bits if b))
        self.set_thumbnail(thumb)

    def set_thumbnail(self, image):
        self.thumb_source = image
        if image is not None:
            self.thumb_image = ImageTk.PhotoImage(image)
            self.thumb_label.configure(image=self.thumb_image)
        else:
            self.thumb_image = None
            self.thumb_label.configure(image=self.placeholder_image)

    # ---------- downloading ----------

    def start_download(self):
        url = self.url_var.get().strip()
        if not re.match(r"https?://", url):
            messagebox.showinfo(APP_NAME, "Paste a video link first (it starts with https://).")
            return
        folder = self.folder_var.get().strip()
        try:
            Path(folder).mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            messagebox.showerror(APP_NAME, f"Can't use this folder:\n{folder}\n\n{exc}")
            return
        self.save_settings()
        title = self.info.get("title") if self.info and self.info_url == url else None
        job = Job(url, self.current_options(), title)
        self.jobs[job.id] = job
        self.tree.insert("", 0, iid=job.id, values=(job.title, job.status))
        self.job_queue.put(job)
        self.url_var.set("")
        self.show_info(None)
        self.url_entry.focus_set()

    def download_worker(self):
        self.engine_ready.wait()
        while True:
            job = self.job_queue.get()
            if job.cancelled or job.id not in self.jobs:
                continue
            with self.engine.use():
                self.download(job)

    def build_download_args(self, job):
        o = job.options
        temp = TEMP_ROOT / job.id
        args = self.common_args(o) + [
            "--no-playlist", "--newline", "--no-simulate", "--no-quiet", "--progress",
            "-P", f"home:{o['folder']}", "-P", f"temp:{temp}",
            "-o", "%(title).90B [%(id)s].%(ext)s",
            "--progress-template",
            "download:[dl] %(progress.downloaded_bytes)s|%(progress.total_bytes)s|"
            "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s",
            "--print", "before_dl:[title] %(title)s",
            "--print", "after_move:[file] %(filepath)s",
        ]
        if o["mode"] == "audio":
            args += ["-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0"]
        else:
            sort = QUALITIES.get(o["quality"], "res")
            args += ["-S", f"{sort},vcodec:h264,acodec:aac", "--merge-output-format", "mp4",
                     "--remux-video", "mp4"]
        return args + [job.url], temp

    def download(self, job):
        args, temp = self.build_download_args(job)
        self.post(self.set_job_status, job, "Starting…")
        self.post(self.progress.configure, {"value": 0})
        self.post(self.set_status, f"Downloading: {job.title}")
        log = []
        part = 0
        last_done = 0
        try:
            job.proc = run_hidden(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=1)
            for line in job.proc.stdout:
                line = line.rstrip()
                if not line:
                    continue
                if line.startswith("[dl] "):
                    fields = [_num(v) for v in line[5:].split("|")]
                    if len(fields) != 5:
                        continue
                    done, total, estimate, speed, eta = fields
                    total = total or estimate
                    if done is not None and done < last_done:
                        part += 1  # a new stream (e.g. audio after video) started
                    last_done = done or 0
                    if total:
                        pct = min(100.0, (done or 0) * 100 / total)
                        label = f"{pct:.0f}%"
                        if part:
                            label = f"{label} (part {part + 1})"
                        self.post(self.progress.configure, {"value": pct})
                        self.post(self.set_job_status, job, f"Downloading {label}")
                        detail = [label, format_bytes(total)]
                        if speed:
                            detail.append(f"{format_bytes(speed)}/s")
                        if eta is not None:
                            detail.append(f"{int(eta)}s left")
                        self.post(self.set_status, "   ".join(detail))
                    continue
                if line.startswith("[title] "):
                    self.post(self.set_job_status, job, job.status, line[8:])
                elif line.startswith("[file] "):
                    job.filepath = line[7:]
                elif line.startswith(("[Merger]", "[VideoRemuxer]", "[FixupM3u8]", "[FixupM4a]")):
                    self.post(self.set_job_status, job, "Finishing…")
                    self.post(self.progress.configure, {"value": 100})
                elif line.startswith("[ExtractAudio]"):
                    self.post(self.set_job_status, job, "Converting to MP3…")
                    self.post(self.progress.configure, {"value": 100})
                log.append(line)
            job.proc.wait()
        except Exception as exc:
            log.append(f"ERROR: {exc}")
            kill_tree(job.proc)
        finally:
            shutil.rmtree(temp, ignore_errors=True)

        if job.cancelled:
            self.post(self.set_job_status, job, "Cancelled")
            self.post(self.set_status, "Cancelled.")
        elif job.proc and job.proc.returncode == 0 and job.filepath:
            self.post(self.set_job_status, job, "Done", Path(job.filepath).name)
            self.post(self.set_status, f"Saved: {Path(job.filepath).name}", "Ok")
        else:
            message = friendly_error("\n".join(log[-40:]))
            self.post(self.set_job_status, job, "Failed")
            self.post(self.set_status, message, "Error")
            self.post(messagebox.showerror, APP_NAME, f"Download failed:\n\n{message}")
        self.post(self.progress.configure, {"value": 0})

    # ---------- closing ----------

    def on_close(self):
        running = [j for j in self.jobs.values() if j.proc and j.proc.poll() is None]
        if running and not messagebox.askyesno(APP_NAME, "A download is still running. Stop it and quit?"):
            return
        for job in self.jobs.values():
            job.cancelled = True
            kill_tree(job.proc)
        self.save_settings()
        self.root.destroy()


def _num(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def main():
    with contextlib.suppress(Exception):
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # crisp text on high-DPI screens
    # own taskbar identity, so Windows shows our icon rather than Python's
    with contextlib.suppress(Exception):
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("VideoDownloader.App")
    # Video Downloader.exe [--download] [LINK]: open with LINK ready, or start downloading it
    links = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = tk.Tk()
    App(root, initial_url=links[0] if links else None, auto_download="--download" in sys.argv[1:])
    root.mainloop()


if __name__ == "__main__":
    main()
