"""ndi_snap.py — one picture from the camera's NDI stream, as a receiver sees it (no overlay, the whole frame).
Marko, 26.10.2026: "to see how the controller looks, please use my mantra camera, the mantra manual camera".

    python3 scripts/ndi_snap.py out.png [--source "Pixel 7"] [--extra-ips 192.168.1.101] [--width 1400]

Uses the NDI runtime through ndi_probe.py's bindings, asks for BGRA, and keeps the first video frame that arrives.
"""
import argparse, ctypes, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ndi_probe import load, bind, find, RecvCreate, Video, Audio, Meta, FRAME_VIDEO, BANDWIDTH_HIGHEST  # noqa: E402

COLOR_BGRX_BGRA = 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--source", default="")
    ap.add_argument("--extra-ips", default="")
    ap.add_argument("--width", type=int, default=0, help="scale the picture to this width")
    ap.add_argument("--timeout", type=float, default=15)
    a = ap.parse_args()
    lib, _ = load(); bind(lib)
    if not lib.NDIlib_initialize():
        sys.exit("NDI did not initialise")
    f, src, name = find(lib, a.source, a.extra_ips, a.timeout)
    rc = RecvCreate(src, COLOR_BGRX_BGRA, BANDWIDTH_HIGHEST, False, b"ndi_snap")
    r = lib.NDIlib_recv_create_v3(ctypes.byref(rc))
    v, au, m = Video(), Audio(), Meta()
    deadline = time.time() + a.timeout
    while time.time() < deadline:
        t = lib.NDIlib_recv_capture_v2(r, ctypes.byref(v), None, None, 1000)
        if t != FRAME_VIDEO:
            continue
        w, h, stride = v.xres, v.yres, v.line_stride_in_bytes
        raw = ctypes.string_at(v.p_data, stride * h)
        lib.NDIlib_recv_free_video_v2(r, ctypes.byref(v))
        from PIL import Image
        im = Image.frombuffer("RGBA", (w, h), raw, "raw", "BGRA", stride, 1).convert("RGB")
        if a.width and w > a.width:
            im = im.resize((a.width, int(h * a.width / w)))
        im.save(a.out)
        print(f"{name}: {w}x{h} → {a.out}")
        break
    else:
        print("no picture arrived")
    lib.NDIlib_recv_destroy(r); lib.NDIlib_find_destroy(f)


if __name__ == "__main__":
    main()
