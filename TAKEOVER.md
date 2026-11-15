**15.11.2026, overnight (cloud session) — GO LIVE, phase 2.** State now, newest first:

- **Released tonight on main, all built by GitHub Actions:** v200 (cloud: the native VDO.Ninja publisher, LIVE key,
  Settings LIVE pairing, TEST lens), v201–v203 (Mac session, the same night: the Sony becomes the lens by itself,
  SONY MODE, GO LIVE landscape for the Sony, the Sony bulk stream ended with CLEAR_FEATURE), v204 (cloud: STREAMING as
  the first settings tab, LIVE goes on with the app minimised, pairing by a link opened on the same phone, launch
  extras for unattended tests), v205 (cloud: a viewer that blinks to DISCONNECTED gets 15 s, these documents).
- **Proven:** (1) the password scheme: the unit test reproduces the worked example of docs/VDO_NINJA_SIGNALLING.md byte
  for byte; (2) in a software Android 11 emulator in the cloud, with a made-up id: the app seeded on wss.vdo.ninja, a
  real VDO.Ninja viewer page (headless Chromium) asked, our encrypted offer was accepted, its answer decrypted, ICE
  reached CONNECTED; (3) the site path: /api/live with a made-up token answers HTTP 401 {"error":"login"} and the
  app reads it as "pair again"; (4) on Marko's Pixel 7 with the Sony (Mac session, 22:50): a viewer received the
  stream (it was 1080x1920, turned; fixed in v202); (5) WebRTC's own microphone runs beside the take's (two active
  recording sessions, both stereo, on the emulator).
- **Not proven:** the picture moving in a viewer from a cloud machine (the emulator's software GPU draws nothing at all,
  even its own keys are black in a screenshot), latency, three viewers at once, Opus stereo at the viewer, H.264 vs VP8
  as negotiated, the QR pairing read from the picture, GO LIVE against the real site (needs Marko's Google pairing;
  never pressed by a cloud session), streaming while minimised on a real phone.
- **Marko's VM was not reachable:** the mailbox runner (vm-android-jobs, pixel7-jobs) did not pick up a job in 40 min
  (job 20261114-mmc-probe196 still waiting); the tailnet allows this cloud node only Tailscale SSH to chromium-vm, and
  SSH was refused by the session's permission policy. The emulator in the cloud container stood in.
- **Carried forward from v193–v196 (needs Marko and the real Sony):** motion, fps vs the Sony's modes, stereo sound from
  the Sony, whether the Sony's standby clears while streaming.
- **Next:** on the phone: GO LIVE against the site after pairing; a viewer count with 3 viewers; the LIVE picture
  through a lens change (the stage is rebuilt, the LIVE surface is attached again: watch for a freeze, LESSONS 30).


**7.11.2026, 23:10 — v188 released (Latest):** picture and sound of a take on one clock, both starting at 0 (the Santoshi take had its picture 4911 s after its sound: boot clock against the monotonic clock). Built on GitHub after two fixes: three ceres sources were kept out of git by the *token* rule; the local folder worked on branch claude/mantra-manual-camera-rename-mxa5ln while main stood at v182 (main fast-forwarded, 52 commits). Not yet tested with a recording. Stereo: off by default (Settings audioStereo); Marko asked why his take is mono (answer given, not changed).

**15.11.2026, 06:22 — v217 installed (Latest), v216 before it:** Sound Link's buffer learns the longest arrival gap and covers it,
trims after a dropout and at an oversized first start (LESSONS "Sound Link holes ... v216–v217"). Builds:
https://github.com/markoboskoauroville/mantra-manual-camera/actions/runs/43678213275 (v216, green),
https://github.com/markoboskoauroville/mantra-manual-camera/actions/runs/43679511267 (v217, green).
