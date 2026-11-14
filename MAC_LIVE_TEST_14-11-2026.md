# Mac live test for the GO LIVE cloud session (14.11.2026, 22:10 Croatia / 21:10 UTC)

From the Mac session claude-code-8a, at Marko's request: "test now and stream to the website of Santoshi from the Sony camera ... report testing and what needs to be built to the cloud session".

## Tested on the real Pixel 7 (adb 100.70.23.53:5555), Manual Camera v196
- PASS: the Sony α7 V (ILCE-7M5) on USB is the lens: SONY on the lens key, live picture in the app (screenshot taken 22:10).
- PASS: sound from the Sony: top line "Sound: USB-C · USB-Audio - ILCE-7M5 · stereo".
- Battery 100%, 2:47 h; the app is in front; the Sony Remote app is also registered on the Sony USB device (package com.mantraproductions.sonyremote): it must not take the camera away.
- NOT POSSIBLE: streaming to the Santoshi site. v196 has no publisher: no VDO.Ninja / WebRTC / GO LIVE code in app/src. The outputs on the top line are FILE.USB.NDI.YT.SONY only.

## What must be built (your night job, from v197)
1. GO LIVE key in Manual Camera: native VDO.Ninja publisher (docs/VDO_NINJA_SIGNALLING.md), the GPU-stage picture (incl. the SONY lens) + the Sony's stereo USB audio as the WebRTC tracks.
2. Pairing with the Santoshi site: scan the PAIR CAMERA QR (mantracam://pair) on admin LIVE, keep the Bearer token, call /api/live go / stop / status.
3. The stream must keep the Sony lens (not fall back to the phone camera) and stereo audio; show LIVE red + viewers on the top line.
4. Marko is ready to test tonight: the Sony is connected and streaming into the Pixel now. As soon as a build is green and Latest, write here (or to the Mac session) and the Mac installs the release APK on the Pixel and runs the live test against the Santoshi site viewer.
