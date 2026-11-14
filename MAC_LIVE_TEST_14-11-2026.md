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

## NEW REQUEST from Marko, 14.11.2026 ~22:20 (word for word, also in momentaryupdates.md). This shapes the GO LIVE design.

> "for this to be understandable and easy to work, you need to build one more tab inside the settings, and that is the default first tab: streaming. In the streaming, I can select which streaming is available right now at this moment while the camera is working. One of these streamings will be VDO Ninja. When I press Video Ninja, I can minimize this app on the same phone. I can go to my Santoshi page, log in as an administrator, click Stream, and the camera will stream To my Santoshi stream."

How the Mac session reads it (build it this way unless the spec says otherwise):
1. Settings gets a new first tab STREAMING, opened by default when settings open (the settings open from the time/space key, which now gets a white square: Mac worker, v197 or next free number).
2. STREAMING lists the outputs that can run right now (VDO Ninja first; NDI, YouTube, USB as they exist), each one key, lit when on.
3. Pressing VDO NINJA starts the native publisher at once (the Sony lens if selected + its stereo audio) in a foreground service (camera + microphone types, notification "Streaming to Santoshi"), so it keeps sending when the app is minimised.
4. Same phone, no QR: the camera already knows the Santoshi site (the pairing token stored once; offer "pair this phone" as a mantracam://pair link the admin page opens on the same phone, besides the QR for another phone).
5. On santoshi site admin LIVE, the Stream button (/api/live go with the token) makes the site show that camera's VDO.Ninja stream to viewers; STOP ends it. The camera shows LIVE red when the site has gone live.
6. Test path Marko will do: press VDO NINJA, home button, open the Santoshi page in Chrome on the same Pixel, sign in as admin, press Stream, and the viewer page plays the Sony picture with sound.
