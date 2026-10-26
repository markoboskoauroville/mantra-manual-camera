# Mantra Manual Camera: features for the product page (from the local Mac session, 26.10.2026, state v154)

Written by the Mac session that builds the app, for the product page and the santoshi-band drawer. Every
"PROVEN" line was measured on Marko's phones; "COMING" is not built yet. Long-form source: README.md.

## Names, as Marko uses them today
- **Mantra Manual Camera**: the camera app on the phone that shoots (reference phone: Google Pixel 7).
- **Camera Remote**: the second app, on another phone or tablet, that watches and drives the camera. Its old
  name was Mantra Monitor; renamed 26.10.2026 (launcher label "Camera Remote").
- **Computer Remote**: a different product (repo mantra-remote, formerly Mantra Remote): the phone controls
  the Mac. Not part of the camera; do not put it on this page except as "also by Mantra".
- Both camera apps ship together in every release.

## Price, trial, link
- **€22** (README: "Why it is worth €22"); the store version will have a **free trial** ("try every feature on your own phone before you pay").
- Latest release (both APKs): https://github.com/markoboskoauroville/mantra-manual-camera/releases/latest
- v153 is the latest *released*; v154 (the tunnel, the quality rail) is built and on his phones, release pending his test.
- Page address: Marko has not named one in this session. Ask him.

## PROVEN on the Pixel 7 (camera) and the Nothing Phone 2a (remote), as of v154
- **Manual control, fader by fader**: ISO, shutter, focus, white balance in Kelvin, each on a mixer-style fader with its own A/M switch (half manual or full manual).
- **Log curves**: Sony S-Log3, Panasonic V-Log, ARRI LogC3/LogC4, Blackmagic Film Gen5, in the file and on the stream; 10-bit HLG HEVC recording.
- **NDI HX and full NDI** out (vMix, OBS, NDI Studio Monitor), over Wi-Fi or a USB cable.
- **FULL**: a truly clean feed, nothing on the glass but the picture.
- **Focus**: tap to focus, a focus mark you pinch from eye size to the whole frame; the **focus-puller meter**: a distance scale (2 m … 15 cm) with keyframes you add and remove (long press 2 s, ADD / REMOVE); **rack focus** between keyframes at a speed in mm/s; distance shown as "FOC: 21.1 cm".
- **LUTs** (.cube), **focus peaking**, **false colour**, **zebra**, on the monitor only (recording keeps full range).
- **□ ○ △ marks** on the picture (framing marks).
- **Settings in four tabs**: FOCUS, TRACK, SOUND, LOOK.
- **The LED as a light**, brightness on a fader.
- **Timecode, REC in the middle, free space and hours left**, PLAY of the last take (a round key).
- **Every lens, one key each**; the lens is remembered.
- **Camera Remote over ADB** (wireless debugging, scrcpy inside): the camera's own screen on the remote phone, every touch goes back; measured 31 fps on Wi-Fi.
- **Anywhere in the world, through a free VPN (Tailscale)**: proven 26.10.2026 with the remote on 4G mobile data, Wi-Fi off: "ADB · Pixel 7 · 100.70.23.53 · 17 fps"; the link survives walking out from Wi-Fi to 4G without a break.
- **Quality for slow networks, set in the Camera Remote** (v154, like the Computer Remote): FPS 30 · 24 · 15 · 10 · 5 · 2 · 1; SCALE 1.0–0.5 (1600 px down to 800 px); MBIT 8 · 4 · 2 · 1 · 0.5 · 0.25; B/W; Wi-Fi and 4G each remember their own choice (4G starts light: 15 FPS, 0.7, 1 Mbit). Status line: "ADB · Pixel 7 · 4G · 12.3 MB · 950 kbit/s · 15 fps of 15 · SCALE 0.7 · 1 MBIT".
- **Every setting reachable over ADB** (the `mcam` script): a studio computer can drive the camera.
- **An UPDATE key**: the app updates itself from the latest release.
- Measured frame timing: 676 frames, every gap 33.3 ms.

## PTZ
- **No pan/tilt motor control.** The phone has no motors; there is no PTZ head support in the app today.
- What exists from the remote: **focus** (pull, rack between keyframes, at a set speed), **lens choice** (wide / main / tele: the phone's "zoom" is its lenses), exposure and white balance, REC, all from the Camera Remote.
- A PTZ head (motorised gimbal) has not been asked for in this session. If Marko wants "PTZ" on the page, it should say "remote focus, lens and exposure", not pan/tilt.

## COMING (not built)
- Exposure that knows the log curve (middle grey where each manufacturer puts it).
- Grey-card white balance sweep; a colour-meter companion.
- Waveform and vectorscope tiles; the phone as a webcam for a Mac; tracking focus; a live settings preview.
- Known open bug (do not advertise around it): after switching NDI HX/FULL while streaming, the picture can stop on the receiver while sound continues (found 26.10.2026, being fixed).

## Screenshots and Nano Banana pictures
Not in this push. The screenshots need the Pixel switched through FULL, false colour, LUT and settings, which
would disturb Marko's live test of the camera; they come when the phone is free. The three Nano Banana renders
need his Chrome (Gemini accounts) and come after the screenshots.
