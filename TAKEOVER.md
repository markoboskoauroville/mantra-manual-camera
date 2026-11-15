**15.11.2026, 09:25 — v220 released (Latest), NOT yet installed on the Pixel (waiting for Marko to watch):** Settings and the
Sony Remote screen are always portrait (manifest; PairActivity and UsbAttachActivity are translucent trampolines and stay as
they are, since a fixed orientation on a translucent activity crashes Android 8.0). Every user-facing "band site" says "the
Santoshi band site" (OUTPUTS switch "SANTOSHI BAND SITE", STREAMING texts, messages, help; top line "SANTOSHI n"). LIVE colours:
red while NDI or the site really sends, amber while connecting or waiting, grey when off; a waiting NDI says why ("LIVE · NDI
waiting (USB CABLE)"). Build (green): https://github.com/markoboskoauroville/mantra-manual-camera/actions/runs/43693680664.
At 09:14 Marko's LIVE hung on "NDI…" because NDI NETWORK was USB CABLE (put back by my 08:40 test, LESSONS); the Mac
(192.168.1.100) and the Pixel (192.168.1.101) are now both on the TP-Link "mantra" Wi-Fi and ping each other. Next: install
v220, set NDI NETWORK to WI-FI in front of Marko, prove the picture in NDI Video Monitor (ndimon), then the HX preview stream
and the 40-min trial soak.

**15.11.2026, 08:50 — v219 installed (Latest):** from the NDI review (MANTRA_MANIFEST modules/ndi-best-practice.md): NDI source
names lose "(" ")" (sanitizeSourceName; a saved name with them is cleaned when sent), and the ndi-video thread asks the encoder
for a keyframe as soon as NDIlib_send_is_keyframe_required says a receiver waits (at most every 300 ms; "keyAsks" in the NDI LIVE
trace line). Build (green): https://github.com/markoboskoauroville/mantra-manual-camera/actions/runs/43690959742. On the Pixel:
LIVE lists "Pixel 7 Camera" over Tailscale. **Open, need a network where the Mac receives the phone's picture** (the hotspot
"Something" refuses Mac→phone on the LAN, the Mac being on the cable en17): the HX preview stream (~640 px, *_lowest_bandwidth,
a second encoder and its own parameter sets in ndi_bridge.cpp, which today keeps one g_video_extra), the 40-minute Advanced SDK
trial soak (docs: Android stops after 5 min without a License ID) and a make-before-break sender refresh if it drops, and the
proof in NDI Video Monitor (`ndimon select "Pixel 7 Camera"`, `ndimon shot`).

**15.11.2026, 08:40 — v218 installed on the Pixel 7 (Latest): RECORD records, LIVE streams.** Marko: "record records, and to
start streaming we go live, and that's it." The record key records the file only. The LIVE key starts every stream switched on
under LIVE in Settings, OUTPUTS (NDI, BAND SITE; YouTube and USB still dark) and a two-second hold stops them all. NDI no longer
starts when the camera opens. OUTPUTS is two groups, "RECORD (record key)" (FILE + folder) and "LIVE (live key)" (NDI with
HX/FULL, wire, sound, name; BAND SITE, new switch armSite, on by default; YOUTUBE; USB). The STREAMING tab keys only choose
the way (SANTOSHI RELAY / VDO NINJA), they no longer go live. Top line: "● LIVE · NDI · SITE n"; the LIVE key shows NDI or the
viewer count. Also in v218: NDI "SOUND IN THE STREAM" off by default and switched off once for everybody (the Mac adds the sound
through Sound Link), and the picture gets the sound's 1.5 Mbit/s. Build (green):
https://github.com/markoboskoauroville/mantra-manual-camera/actions/runs/43689821007. Tested on the Pixel with NDI on and the
band site off: REC → no NDI source on the Mac; LIVE → "Pixel 7 Camera" listed; LIVE stop → gone. The hotspot "Something" refuses
every Mac→phone TCP connection on 10.121.232.x (even adb), so discovery went over Tailscale (100.70.23.53), and no picture frames
arrived there (LESSONS). The band site was not touched (Marko: nothing on the internet until he is back).

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
