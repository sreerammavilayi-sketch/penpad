# PenPad - phone as a pen tablet (no developer setup)

## One-time: get the two apps (free, ~10 min, no coding)
1. Make a free account at github.com -> New repository (e.g. "penpad").
2. Upload everything from this folder (drag the folder contents into "uploading an existing file").
   If the hidden `.github` folder is skipped: Add file -> Create new file, name it
   `.github/workflows/build.yml` and paste the file from this folder.
3. Open the **Actions** tab -> "Build PenPad" -> Run workflow. Wait ~5 min.
4. Open the finished run -> **Artifacts** -> download `PenPad-Windows` (PenPad.exe) and
   `PenPad-Android` (app-debug.apk).

## One-time: install
- Phone: open the APK, allow "install unknown apps" when asked.
- PC: run PenPad.exe. SmartScreen: "More info" -> "Run anyway" (the exe is unsigned).
  Windows Firewall: tick Private AND Public networks -> Allow. A tray icon appears.
  Right-click tray icon -> "Start with Windows" to launch it automatically.

## Every time
1. Phone: turn on **USB tethering** (Settings -> Network -> Hotspot & tethering) with the cable plugged in
   (or put phone and PC on the same Wi-Fi).
2. Open PenPad on the phone. First time only, the PC asks "Allow this phone?" -> Yes.

## Use
- MOVE (grey): drag = move PC cursor. Double-tap = DRAW (green): cursor spot is the origin, writing stays on a flat line.
- Write to the right edge, lift, restart at the left: the line continues (wraps at screen edge).
- Top-right button: INK (strokes drawn on PC) / TEXT (recognised on phone, typed on PC).
- Settings (band size, speed...) are at the top of pc/penpad.py; language LANG in MainActivity.kt.
