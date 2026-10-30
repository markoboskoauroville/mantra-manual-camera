# GPU tracker: where the new session starts (30.10.2026)

Marko, 30.10.2026: *"move all the tracking, processing, and calculation to the GPU"*, then *"Yes, create the new
session, and let's build this."*

**Read first, in this order:** this file; `momentaryupdates.md` from "## 30.10.2026: the swinging tennis ball" to the
end (the measurements and the 8-point plan); `LESSONS.md` 34 (libmv proved on the Mac first) and the 30.10.2026 block;
`app/src/main/java/com/mantraproductions/ndi/GpuStage.kt` (the old GPU NCC search, `nccProgram`, the luma copy
640x360, `startTracking`, `libmvStep`); `LibmvTracker.kt`; `MainActivity.onTracked`.

**Where it stands (v185, on the Pixel 7 41241FDH2S10SJ):** TRK = libmv LOC on the CPU, every 2nd frame, search x2.5,
confidence 0.60. After a restart it held the swinging ball (with feathers) for 19 s, 0.97 → 0.88, 20–32 ms a step.
It fails when the pattern is taken late (mostly background) and it never re-finds the subject once LOST (494x360
search, 1.66 s a step). The old GPU NCC (to v158) lost the ball; libmv (v160) won: 0 of 477 frames lost on the Mac.

**The build (the plan in momentaryupdates.md, points 1–8):** a luma pyramid on the GPU, coarse-to-fine NCC,
inverse-compositional Lucas–Kanade refinement as a shader (translation only), a max-reduction shader (one pixel read
back), constant-velocity prediction, a whole-frame 1/4 re-find after LOST, the pattern from the tap's own frame,
every frame, position and score in the trace on every step. libmv stays as the fallback until the GPU tracker beats
it on the same swinging ball (lost frames, pixel error), proven from the trace.

**How to test:** the ball hangs in front of the Pixel; `scripts/mcam` (state, settings, set, key), real touches with
`adb shell input` (F green first; AF key → TRK at 310,576 in the flipped landscape), the trace in
`/sdcard/Android/data/com.mantraproductions.manualcamera/files/trace-*.txt`. Build and release with
`scripts/release_mac.sh` (built on the Mac, signed, both APKs, Latest); the site publishes itself (site.yml).
Ask Marko (orange line) for anything that needs his hand, one thing at a time.
