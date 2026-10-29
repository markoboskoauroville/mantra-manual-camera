#!/usr/bin/env python3
"""The launcher icons of Mantra Manual Camera and Mantra Monitor, in the style of TTT's (v151, Marko, 26.10.2026:
"release the next version with the icon in the same style as the ttt advanced keyboard icon").

TTT's language (TTT_MINI design/make_app_icon.py): five dark rounded keys on #0D1117, three above and one small and one
wide below, each with its legend lit in its own colour and a soft glow behind it, the build number drawn under the keys.
Here the legends are each app's own:

    CAMERA   ▢ the focus square (green)  ○ the ISO circle (amber)  ● REC (red)   /  a lens (cyan)  the focus meter (violet)
    MONITOR  ▶ play (green)              ▯ the camera phone (cyan) ● REC (red)   /  Wi-Fi (amber)  the focus meter (violet)

Everything stays inside the 42-unit symbol box (33 … 75, modules/app-icon.md). The build writes the version digits at
<!--VERSION--> (app/build.gradle.kts, monitor/build.gradle.kts). Run from the repo root: python3 scripts/make_icons.py
"""
import os

RED, AMBER, CYAN, VIOLET, GREEN, WHITE = "FF4D5E", "FFC24D", "4DD6E8", "B98CFF", "33D17A", "F2F2F2"
KEY_FILL, KEY_EDGE, GROUND = "#1A1F27", "#2C323E", "#0D1117"

# the five keys, as TTT has them: (x0, y0, x1, y1)
K1, K2, K3 = (33.3, 34.3, 45.7, 48.7), (48.8, 34.3, 61.2, 48.7), (64.3, 34.3, 76.7, 48.7)
K4, K5 = (33.3, 51.0, 45.7, 61.0), (48.8, 51.0, 76.7, 61.0)


def n(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def key(k):
    x0, y0, x1, y1 = k
    r = 2.2
    d = (f"M{n(x0 + r)},{n(y0)} H{n(x1 - r)} A{r},{r} 0 0 1 {n(x1)},{n(y0 + r)} V{n(y1 - r)} "
         f"A{r},{r} 0 0 1 {n(x1 - r)},{n(y1)} H{n(x0 + r)} A{r},{r} 0 0 1 {n(x0)},{n(y1 - r)} V{n(y0 + r)} "
         f"A{r},{r} 0 0 1 {n(x0 + r)},{n(y0)} Z")
    return f'    <path android:pathData="{d}" android:fillColor="{KEY_FILL}" android:strokeColor="{KEY_EDGE}" android:strokeWidth="0.6" />\n'


def glow(k, colour, sx=1.0, sy=1.0):
    """TTT's soft light behind a legend: a radial gradient from 70 % to nothing."""
    cx, cy = (k[0] + k[2]) / 2, (k[1] + k[3]) / 2
    r = 7.69
    return (f'    <group android:pivotX="{n(cx)}" android:pivotY="{n(cy)}" android:scaleX="{sx}" android:scaleY="{sy}">\n'
            f'        <path android:pathData="M{n(cx - r)},{n(cy)} a{r},{r} 0 1 0 {n(2 * r)},0 a{r},{r} 0 1 0 {n(-2 * r)},0 Z">\n'
            f'            <aapt:attr name="android:fillColor">\n'
            f'                <gradient android:type="radial" android:centerX="{n(cx)}" android:centerY="{n(cy)}" android:gradientRadius="{r}">\n'
            f'                    <item android:offset="0" android:color="#B3{colour}" />\n'
            f'                    <item android:offset="0.45" android:color="#59{colour}" />\n'
            f'                    <item android:offset="1" android:color="#00{colour}" />\n'
            f'                </gradient>\n            </aapt:attr>\n        </path>\n    </group>\n')


def stroke(d, colour, w=1.3):
    return (f'    <path android:pathData="{d}" android:strokeColor="#{colour}" android:strokeWidth="{w}" '
            f'android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" />\n')


def fill(d, colour):
    return f'    <path android:pathData="{d}" android:fillColor="#{colour}" />\n'


def centre(k):
    return (k[0] + k[2]) / 2, (k[1] + k[3]) / 2


def circle(cx, cy, r):
    return f"M{n(cx - r)},{n(cy)} a{r},{r} 0 1 0 {n(2 * r)},0 a{r},{r} 0 1 0 {n(-2 * r)},0 Z"


def focus_square(k):
    cx, cy = centre(k); h = 3.4; a = 1.5
    d = []
    for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        x, y = cx + sx * h, cy + sy * h
        d.append(f"M{n(x)},{n(y - sy * a)} V{n(y)} H{n(x - sx * a)}")
    return stroke(" ".join(d), GREEN)


def iso_circle(k):
    cx, cy = centre(k)
    return stroke(circle(cx, cy, 3.5), AMBER)


def rec(k):
    cx, cy = centre(k)
    return fill(circle(cx, cy, 3.1), RED)


def lens(k):
    cx, cy = centre(k)
    return stroke(circle(cx, cy, 2.9), CYAN, 1.1) + fill(circle(cx, cy, 1.0), CYAN)


def meter(k):
    """The focus puller's meter: its line, two key points (empty triangles) and the lens (a full white triangle)."""
    x0, x1 = k[0] + 3.0, k[2] - 3.0
    y = k[3] - 3.0
    out = stroke(f"M{n(x0)},{n(y)} H{n(x1)}", VIOLET, 0.9)
    for t in (0.18, 0.5, 0.82):
        x = x0 + (x1 - x0) * t
        out += stroke(f"M{n(x)},{n(y)} V{n(y + 1.3)}", VIOLET, 0.6)

    def tri(x, filled, colour):
        d = f"M{n(x)},{n(y - 0.6)} L{n(x - 1.6)},{n(y - 3.4)} L{n(x + 1.6)},{n(y - 3.4)} Z"
        return fill(d, colour) if filled else stroke(d, colour, 0.8)
    xs = [x0 + (x1 - x0) * t for t in (0.22, 0.48, 0.78)]
    return out + tri(xs[0], False, VIOLET) + tri(xs[2], False, VIOLET) + tri(xs[1], True, WHITE)


def play(k):
    cx, cy = centre(k)
    return fill(f"M{n(cx - 2.4)},{n(cy - 3.6)} L{n(cx + 3.6)},{n(cy)} L{n(cx - 2.4)},{n(cy + 3.6)} Z", GREEN)


def phone(k):
    cx, cy = centre(k); w, h = 2.6, 4.4
    return (stroke(f"M{n(cx - w)},{n(cy - h)} H{n(cx + w)} V{n(cy + h)} H{n(cx - w)} Z", CYAN, 1.1)
            + stroke(f"M{n(cx - 0.8)},{n(cy + h - 1.3)} H{n(cx + 0.8)}", CYAN, 0.8))


def wifi(k):
    cx, cy = centre(k); cy += 2.3
    out = fill(circle(cx, cy, 0.8), AMBER)
    for r in (2.4, 4.2):
        out += stroke(f"M{n(cx - r * 0.72)},{n(cy - r * 0.69)} A{r},{r} 0 0 1 {n(cx + r * 0.72)},{n(cy - r * 0.69)}", AMBER, 1.0)
    return out


def icon(title, legends, glows):
    head = ('<?xml version="1.0" encoding="utf-8"?>\n<!--\n'
            f'     {title}, the launcher icon, in the style of TTT\'s (v151). GENERATED by scripts/make_icons.py; change it there.\n'
            '     THIS IS A TEMPLATE: the build writes the version digits at the VERSION line (version-visible.md).\n-->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n')
    body = "".join(key(k) for k in (K1, K2, K3, K4, K5))
    body += "".join(glow(*g) for g in glows)
    body += "".join(legends)
    return head + body + "    <!--VERSION-->\n</vector>\n"


def lit_icon(title, body):
    """v151 second word (Marko: "Icon is not good. Bring the camera back with the same color styling. That's all"):
    the app's own symbol again, drawn in TTT's colours — lit lines with TTT's glow on its dark ground and key colour."""
    head = ('<?xml version="1.0" encoding="utf-8"?>\n<!--\n'
            f'     {title}, the launcher icon: its own symbol in TTT\'s colours (v151). GENERATED by scripts/make_icons.py.\n'
            '     THIS IS A TEMPLATE: the build writes the version digits at the VERSION line (version-visible.md).\n-->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n')
    return head + body + "    <!--VERSION-->\n</vector>\n"


def glow_at(cx, cy, r, colour, sx=1.0, sy=1.0):
    return (f'    <group android:pivotX="{n(cx)}" android:pivotY="{n(cy)}" android:scaleX="{sx}" android:scaleY="{sy}">\n'
            f'        <path android:pathData="{circle(cx, cy, r)}">\n'
            f'            <aapt:attr name="android:fillColor">\n'
            f'                <gradient android:type="radial" android:centerX="{n(cx)}" android:centerY="{n(cy)}" android:gradientRadius="{r}">\n'
            f'                    <item android:offset="0" android:color="#B3{colour}" />\n'
            f'                    <item android:offset="0.45" android:color="#59{colour}" />\n'
            f'                    <item android:offset="1" android:color="#00{colour}" />\n'
            f'                </gradient>\n            </aapt:attr>\n        </path>\n    </group>\n')


# v95's camera, traced from his launcher's picture (the flash tab, the off-centre hump, the thick lens ring), lifted 4
BODY = "M45.67,38.5 L48.83,38.5 L48.83,35.83 L59.62,35.83 L59.62,38.5 L71.78,38.5 L71.78,62.83 L36.67,62.83 L36.67,41.23 Z"
FLASH = "M38,36.5 L43.2,36.5 L38,38.9 Z"
LX, LY = 54.23, 51.12
camera_body = (
    glow_at(54.2, 49.3, 17, AMBER, 1.0, 0.75)                       # the body's soft light
    + f'    <path android:pathData="{BODY}" android:fillColor="{KEY_FILL}" />\n'
    + stroke(BODY, AMBER, 1.4)                                       # the body, a lit amber line
    + fill(FLASH, RED)                                               # the flash tab, red
    + glow_at(LX, LY, 11, CYAN)                                      # the lens's light
    + stroke(circle(LX, LY, 8.1), CYAN, 2.6)                         # the thick lens ring, cyan
    + fill(circle(LX, LY, 5.4), "0D1117")                            # the glass, dark
    + stroke(circle(LX, LY, 3.2), CYAN, 0.8)
    + fill(circle(LX + 1.9, LY - 1.9, 0.9), WHITE)                   # a glint
)
camera = lit_icon("Mantra Manual Camera", camera_body)

# the monitor's own symbol (until v150 ic_monitor.xml): a screen with the camera's lens in it, now lit the same way
SCREEN = "M35,35 H73 V58 H35 Z"
monitor_body = (
    glow_at(54, 46.5, 17, CYAN, 1.0, 0.7)
    + f'    <path android:pathData="{SCREEN}" android:fillColor="{KEY_FILL}" />\n'
    + stroke(SCREEN, CYAN, 1.4)
    + stroke("M46,62 H62 M54,58 V62", CYAN, 1.4)                     # the stand
    + glow_at(54, 46.5, 7.5, GREEN)
    + stroke(circle(54, 46.5, 5.2), GREEN, 1.8)                      # the camera's lens
    + fill(circle(54, 46.5, 1.6), GREEN)
    + fill(circle(68.5, 39, 1.3), RED)                               # REC
)
monitor = lit_icon("Mantra Monitor", monitor_body)

BACKGROUND = ('<?xml version="1.0" encoding="utf-8"?>\n'
              '<!-- v151: the ground of the icon, TTT\'s #0D1117. GENERATED by scripts/make_icons.py. -->\n'
              '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
              '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
              f'    <path android:fillColor="{GROUND}" android:pathData="M0,0h108v108h-108z" />\n</vector>\n')

ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/{bg}" />\n'
            '    <foreground android:drawable="@drawable/{fg}" />\n'
            '    <monochrome android:drawable="@drawable/{fg}" />\n'
            '</adaptive-icon>\n')

files = {
    "app/src/main/icon/ic_launcher_foreground.xml": camera,
    "app/src/main/res/drawable/ic_launcher_background.xml": BACKGROUND,
    "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml": ADAPTIVE.format(bg="ic_launcher_background", fg="ic_launcher_foreground"),
    "monitor/src/main/icon/ic_monitor_foreground.xml": monitor,
    "monitor/src/main/res/drawable/ic_monitor_background.xml": BACKGROUND,
    "monitor/src/main/res/mipmap-anydpi-v26/ic_monitor.xml": ADAPTIVE.format(bg="ic_monitor_background", fg="ic_monitor_foreground"),
}
for path, text in files.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    print(path)
