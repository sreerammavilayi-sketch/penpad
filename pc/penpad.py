"""PenPad - PC app (Windows 10 1809+ / 11).
Tray app. The phone finds this PC automatically (USB tethering or same Wi-Fi),
pen strokes are injected as a real pen, recognised text is typed."""
import base64, ctypes, json, os, socket, sys, threading, time
from ctypes import wintypes as w
import winreg
import pystray
from PIL import Image, ImageDraw

APP = "PenPad"
PORT = 5555          # phone connects here (TCP)
DISC_PORT = 5556     # this app announces itself here (UDP)

BAND_W = 700         # writing line length on PC (pixels)
BAND_H = 160         # writing line height on PC (pixels)
GAIN = 3.0           # cursor speed in move mode
TRIGGER = 0.7        # auto-advance: previous stroke ended beyond this...
RESTART = 0.4        # ...and next stroke starts before this
GAP = 20             # space between words (pixels)
LINE_SPACING = 1.2   # new-line distance as multiple of BAND_H
CLICK_TO_FOCUS = True
TYPE_SPACE = True

PT_PEN = 3
F_NEW, F_INRANGE, F_INCONTACT, F_FIRSTBTN = 0x1, 0x2, 0x4, 0x10
F_DOWN, F_UPDATE, F_UP = 0x10000, 0x20000, 0x40000
PEN_MASK_PRESSURE = 0x1

class POINTER_INFO(ctypes.Structure):
    _fields_ = [("pointerType", w.DWORD), ("pointerId", ctypes.c_uint32),
        ("frameId", ctypes.c_uint32), ("pointerFlags", w.DWORD),
        ("sourceDevice", w.HANDLE), ("hwndTarget", w.HWND),
        ("ptPixelLocation", w.POINT), ("ptHimetricLocation", w.POINT),
        ("ptPixelLocationRaw", w.POINT), ("ptHimetricLocationRaw", w.POINT),
        ("dwTime", w.DWORD), ("historyCount", ctypes.c_uint32),
        ("InputData", ctypes.c_int32), ("dwKeyStates", w.DWORD),
        ("PerformanceCount", ctypes.c_uint64), ("ButtonChangeType", w.DWORD)]

class POINTER_PEN_INFO(ctypes.Structure):
    _fields_ = [("pointerInfo", POINTER_INFO), ("penFlags", ctypes.c_uint32),
        ("penMask", ctypes.c_uint32), ("pressure", ctypes.c_uint32),
        ("rotation", ctypes.c_uint32), ("tiltX", ctypes.c_int32),
        ("tiltY", ctypes.c_int32)]

class _U(ctypes.Union):
    _fields_ = [("penInfo", POINTER_PEN_INFO)]

class POINTER_TYPE_INFO(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", w.DWORD), ("u", _U)]

ULONG_PTR = ctypes.c_size_t
class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", w.LONG), ("dy", w.LONG), ("mouseData", w.DWORD),
                ("dwFlags", w.DWORD), ("time", w.DWORD), ("dwExtraInfo", ULONG_PTR)]
class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", w.WORD), ("wScan", w.WORD), ("dwFlags", w.DWORD),
                ("time", w.DWORD), ("dwExtraInfo", ULONG_PTR)]
class _IU(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]
class INPUT(ctypes.Structure):
    _fields_ = [("type", w.DWORD), ("u", _IU)]

user32 = ctypes.windll.user32
user32.SetProcessDPIAware()
user32.CreateSyntheticPointerDevice.restype = w.HANDLE

def msgbox(text, flags=0x40):
    return user32.MessageBoxW(0, text, APP, flags | 0x40000)  # TOPMOST

dev = user32.CreateSyntheticPointerDevice(PT_PEN, 1, 1)
if not dev:
    msgbox("PenPad needs Windows 10 (1809 or newer) or Windows 11.", 0x10)
    sys.exit(1)

SW, SH = user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)

# ---------------- state / status ----------------
icon = None
status = "Waiting for phone..."
lock = threading.Lock()
active = None

def set_status(s):
    global status
    status = s
    print(s)
    if icon:
        icon.title = f"{APP}: {s}"
        icon.update_menu()

# ---------------- pen / keyboard injection ----------------
mode = "move"
origin = (0, 0)
pen_down = False
last_px = last_py = 0
line_x0 = 0
prev_end = None
last_nx = 0.0
clicked = False

def inject(flags, px, py, pressure):
    t = POINTER_TYPE_INFO()
    t.type = PT_PEN
    p = t.penInfo
    p.pointerInfo.pointerType = PT_PEN
    p.pointerInfo.pointerId = 0
    p.pointerInfo.pointerFlags = flags
    p.pointerInfo.ptPixelLocation.x = int(px)
    p.pointerInfo.ptPixelLocation.y = int(py)
    p.penMask = PEN_MASK_PRESSURE
    p.pressure = int(max(0.0, min(1.0, pressure)) * 1024)
    user32.InjectSyntheticPointerInput(dev, ctypes.byref(t), 1)

def type_text(text):
    evs = []
    def key(vk=0, scan=0, flags=0):
        i = INPUT(); i.type = 1
        i.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
        evs.append(i)
    for ch in text:
        if ch == "\n":
            key(vk=0x0D); key(vk=0x0D, flags=0x2)
            continue
        b = ch.encode("utf-16-le")
        for k in range(0, len(b), 2):
            unit = b[k] | (b[k + 1] << 8)
            key(scan=unit, flags=0x4)
            key(scan=unit, flags=0x4 | 0x2)
    if evs:
        arr = (INPUT * len(evs))(*evs)
        user32.SendInput(len(evs), arr, ctypes.sizeof(INPUT))

def cursor():
    pt = w.POINT(); user32.GetCursorPos(ctypes.byref(pt)); return pt.x, pt.y

def advance(nx):
    global origin, prev_end
    if prev_end is not None and prev_end > TRIGGER and nx < RESTART:
        ox = origin[0] + prev_end * BAND_W + GAP
        oy = origin[1]
        if ox + BAND_W > SW:
            ox = line_x0
            oy = min(SH - 1, oy + BAND_H * LINE_SPACING)
        origin = (ox, oy)
    prev_end = None

def to_screen(x, y):
    px = origin[0] + x * BAND_W
    py = origin[1] + (y - 0.5) * BAND_H
    return max(0, min(SW - 1, px)), max(0, min(SH - 1, py))

def pen_up():
    global pen_down
    if pen_down:
        inject(F_INRANGE | F_UP, last_px, last_py, 0)
        pen_down = False

def reset_state():
    global mode, pen_down, prev_end, clicked
    mode = "move"; pen_down = False; prev_end = None; clicked = False

def handle(line):
    global mode, origin, pen_down, last_px, last_py, line_x0, prev_end, last_nx, clicked
    f = line.split(",")
    c = f[0]
    if c == "M":
        pen_up()
        mode = f[1]
        if mode == "draw":
            origin = cursor()
            line_x0 = origin[0]; prev_end = None; clicked = False
    elif c == "R" and mode == "move":
        cx, cy = cursor()
        nx = max(0, min(SW - 1, cx + float(f[1]) * GAIN))
        ny = max(0, min(SH - 1, cy + float(f[2]) * GAIN))
        user32.SetCursorPos(int(nx), int(ny))
    elif c in ("D", "V") and mode == "draw":
        nx = float(f[1]); pr = float(f[3])
        new_stroke = (c == "D" or not pen_down)
        if new_stroke:
            advance(nx)
        px, py = to_screen(nx, float(f[2]))
        last_nx = nx
        if new_stroke:
            inject(F_NEW | F_INRANGE | F_INCONTACT | F_FIRSTBTN | F_DOWN, px, py, pr)
            pen_down = True
        else:
            inject(F_INRANGE | F_INCONTACT | F_FIRSTBTN | F_UPDATE, px, py, pr)
        last_px, last_py = px, py
    elif c == "T" and mode == "draw":
        text = base64.b64decode(f[1]).decode("utf-8")
        if CLICK_TO_FOCUS and not clicked:
            user32.mouse_event(0x2, 0, 0, 0, 0); user32.mouse_event(0x4, 0, 0, 0, 0)
            time.sleep(0.2); clicked = True
        type_text(text + (" " if TYPE_SPACE else ""))
    elif c == "U":
        was = pen_down
        pen_up()
        if was:
            prev_end = last_nx

# ---------------- pairing ----------------
DATA_DIR = os.path.join(os.environ.get("APPDATA", "."), APP)
ALLOWED = os.path.join(DATA_DIR, "allowed.json")

def load_allowed():
    try:
        with open(ALLOWED) as fh: return json.load(fh)
    except Exception: return []

def save_allowed(lst):
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(ALLOWED, "w") as fh: json.dump(lst, fh)

def approve(did, name):
    allowed = load_allowed()
    if did in allowed: return True
    r = msgbox(f'Allow the phone "{name}" to control this PC\'s pen and keyboard?', 0x4 | 0x20)
    if r == 6:
        allowed.append(did); save_allowed(allowed); return True
    return False

# ---------------- networking ----------------
def local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return [i for i in ips if not i.startswith("127.")]

def beacon_loop():
    msg = f"PENPAD1,{PORT},{socket.gethostname()}".encode()
    while True:
        for ip in local_ips():
            targets = ["255.255.255.255", ".".join(ip.split(".")[:3] + ["255"])]
            for t in targets:
                try:
                    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    s.bind((ip, 0))
                    s.sendto(msg, (t, DISC_PORT))
                    s.close()
                except OSError:
                    pass
        time.sleep(1)

def client(c, addr):
    global active
    mine = False
    try:
        c.settimeout(70)
        f = c.makefile("r", encoding="utf-8", newline="\n")
        hello = f.readline().strip().split(",", 2)
        if len(hello) < 3 or hello[0] != "HELLO":
            return
        _, did, name = hello
        if not approve(did, name):
            c.sendall(b"NO\n"); return
        c.sendall(b"OK\n")
        c.settimeout(8)
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        with lock:
            if active:
                try: active.close()
                except OSError: pass
            active = c; mine = True
        reset_state()
        set_status(f"Connected: {name}")
        for line in f:
            line = line.strip()
            if line and line != "H":
                try: handle(line)
                except Exception as e: print("bad packet", e)
    except (OSError, ValueError):
        pass
    finally:
        with lock:
            if active is c:
                active = None
                pen_up()
                set_status("Waiting for phone...")
        try: c.close()
        except OSError: pass

def serve(srv):
    while True:
        c, addr = srv.accept()
        threading.Thread(target=client, args=(c, addr), daemon=True).start()

# ---------------- tray ----------------
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"

def launch_cmd():
    if getattr(sys, "frozen", False): return f'"{sys.executable}"'
    return f'"{sys.executable}" "{os.path.abspath(__file__)}"'

def autostart_enabled():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP); return True
    except OSError:
        return False

def set_autostart(on):
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on: winreg.SetValueEx(k, APP, 0, winreg.REG_SZ, launch_cmd())
        else:
            try: winreg.DeleteValue(k, APP)
            except FileNotFoundError: pass

def make_icon():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    d.ellipse((4, 4, 60, 60), fill=(20, 90, 40, 255))
    d.line((20, 46, 44, 18), fill="white", width=6)
    d.ellipse((16, 42, 26, 52), fill="white")
    return img

def main():
    global icon
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind(("0.0.0.0", PORT)); srv.listen(2)
    except OSError:
        msgbox("PenPad is already running.", 0x40); return
    threading.Thread(target=serve, args=(srv,), daemon=True).start()
    threading.Thread(target=beacon_loop, daemon=True).start()

    def on_auto(i, item): set_autostart(not autostart_enabled())
    def on_forget(i, item):
        save_allowed([]); set_status("Paired phones cleared")
    def on_quit(i, item):
        pen_up(); i.stop()

    menu = pystray.Menu(
        pystray.MenuItem(lambda i: status, None, enabled=False),
        pystray.MenuItem("Start with Windows", on_auto, checked=lambda i: autostart_enabled()),
        pystray.MenuItem("Forget paired phones", on_forget),
        pystray.MenuItem("Quit", on_quit))
    icon = pystray.Icon(APP, make_icon(), f"{APP}: {status}", menu)
    icon.run()

if __name__ == "__main__":
    main()
