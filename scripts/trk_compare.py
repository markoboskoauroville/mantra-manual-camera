"""trk_compare.py — the GPU tracker against libmv beside it, from the camera's trace (v186, 30.10.2026).

    python3 scripts/trk_compare.py [trace.txt]     (no file: the newest trace pulled from the Pixel through adb)

Reads the "trk g" (GPU, every frame) and "trk m" (libmv, every trackEvery frames on the same frame) lines of the
last TRK run and counts: steps, lost (L), re-found (R), ms a step, and where both looked at the same frame, how far
apart they put the subject (texels of the 640 luma copy). Plan point 8: libmv stays until the GPU tracker wins.
"""
import os, re, statistics as st, subprocess, sys

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
DIR = "/sdcard/Android/data/com.mantraproductions.manualcamera/files/"
SERIAL = os.environ.get("MCAM_SERIAL", "41241FDH2S10SJ")


def text():
    if len(sys.argv) > 1:
        return open(sys.argv[1]).read()
    name = subprocess.run([ADB, "-s", SERIAL, "shell", f"ls -t {DIR} | grep trace | head -1"],
                          capture_output=True, text=True).stdout.strip()
    return subprocess.run([ADB, "-s", SERIAL, "shell", f"cat {DIR}{name}"], capture_output=True, text=True).stdout


def main():
    runs, cur = [], None
    for line in text().splitlines():
        if "tracking" in line and "CONTROL" in line and " on" in line:
            cur = {"g": {}, "m": {}, "pattern": ""}; runs.append(cur)
        elif cur is not None and "trk pattern" in line:
            cur["pattern"] = line.split("trk pattern:")[1].strip()
        m = re.search(r"trk ([gm]) (\d+) ([\d.-]+) ([\d.-]+) ([\d.-]+) ([FLR]) ([\d.]+)", line)
        if m and cur is not None:
            k, n, x, y, s, f, ms = m.groups()
            cur[k][int(n)] = (float(x), float(y), float(s), f, float(ms))
    if not runs:
        sys.exit("no TRK run in the trace")
    r = runs[-1]
    print("pattern:", r["pattern"])
    for k, name in (("g", "GPU  "), ("m", "libmv")):
        d = r[k]
        if not d:
            continue
        ms = [v[4] for v in d.values()]
        print(f"{name} steps {len(d):5d}  lost {sum(v[3] == 'L' for v in d.values()):4d}  "
              f"re-found {sum(v[3] == 'R' for v in d.values()):3d}  score median {st.median(v[2] for v in d.values()):.3f}  "
              f"ms median {st.median(ms):.1f} max {max(ms):.1f}")
    both = [n for n in r["m"] if n in r["g"] and r["g"][n][3] != "L" and r["m"][n][3] != "L"]
    if both:
        dist = sorted(((r["g"][n][0] - r["m"][n][0]) ** 2 + (r["g"][n][1] - r["m"][n][1]) ** 2) ** 0.5 for n in both)
        print(f"same frame, both found: {len(both)}  apart median {st.median(dist):.2f}  "
              f"90% {dist[int(len(dist) * 0.9)]:.2f}  max {dist[-1]:.2f} texels")
        far = [n for n in both if ((r["g"][n][0] - r["m"][n][0]) ** 2 + (r["g"][n][1] - r["m"][n][1]) ** 2) ** 0.5 > 5]
        if far:
            print("more than 5 apart at steps:", far[:20])
    lostg = [n for n, v in r["g"].items() if v[3] == "L"]
    lostm = [n for n, v in r["m"].items() if v[3] == "L"]
    if lostg: print("GPU lost at:", lostg[:30])
    if lostm: print("libmv lost at:", lostm[:30])


if __name__ == "__main__":
    main()
