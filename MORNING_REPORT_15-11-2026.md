# Morning report, 15.11.2026 (overnight cloud session)

Good morning Marko. Here is what happened with GO LIVE during the night.

## What was built

I built the camera's own VDO.Ninja sender. It is version 200. The camera now talks to VDO.Ninja itself, the same way
the browser page did, so your band site plays it without any computer in between. It sends the lens you are using,
the phone's or the Sony, with its sound, in stereo when the source is stereo.

There is a LIVE key under REC. A tap goes live on your band site. Holding it for two seconds stops it. The top line
says LIVE CONNECTING, and then LIVE with how many people are watching.

Version 204 adds what you asked for at 22:20. Settings now open on a new first tab, STREAMING. In it, VDO NINJA goes
live, and it keeps streaming when you press the home button, so on the same phone you can open the band site and
watch. The pairing with the site is on the same tab: scan the QR, paste the link, or simply open the pairing link on
this phone. The site's key is locked inside the phone and never written into any log.

Version 205 fixes one thing I found while testing: a viewer whose connection blinked for a moment was dropped at
once. Now it gets fifteen seconds to come back.

The Mac session worked the same night and released versions 201, 202 and 203 in between: the Sony becomes the lens
by itself, Sony mode, and the GO LIVE picture now stays horizontal with the Sony.

## What was proven, with numbers

The encryption is right: the test reproduces the worked example in the protocol notes byte for byte, six tests out
of six.

In an Android emulator in the cloud, with a made-up stream name, the camera connected to the real VDO.Ninja server, a
real VDO.Ninja viewer page asked for the stream, accepted the camera's encrypted offer, and the connection reached
CONNECTED in under one second after the answer.

Your band site answers a wrong key with HTTP 401, and the camera understands it as "pair again". I never pressed GO
LIVE against your real site.

From the Mac session's notes: on your Pixel with the Sony, a viewer already received the stream last night; it came
vertical, which version 202 fixed.

## What failed or was not proven

Your virtual machine did not answer. The job mailbox did not pick up a job in forty minutes, and the direct way in
was not allowed from this session. So I tested in an emulator inside the cloud machine instead.

That emulator has no real graphics, so it draws nothing, not even its own buttons. Because of that I could not prove
in the cloud that the picture moves at a viewer, and I could not measure the delay or test three viewers at once.
Those three are for your phone this morning.

Also still waiting for you and the real Sony: smooth motion, frame rate against the Sony's modes, stereo sound from
the Sony, and whether the Sony's standby clears while streaming.

## Your steps this morning, in order

1. Update the camera on your Pixel 7: open the camera, Settings, UPDATE. Take the newest version (205 or later).
2. Plug the Sony in with USB Streaming on, and tap Allow when Android asks.
3. On the Santoshi admin page, LIVE tab, press PAIR CAMERA. In the camera, Settings opens on STREAMING: press PAIR:
   SCAN THE QR and hold the camera on the QR until it says Paired. On the same phone you can instead tap the
   pairing link.
4. GO LIVE test: in STREAMING press VDO NINJA, or tap LIVE on the rail. Open the band site on another device and
   check the picture moves, the sound is there, and the top line counts the viewer. Hold LIVE two seconds to stop.

## Builds (GitHub Actions, all green)

- v204: https://github.com/markoboskoauroville/mantra-manual-camera/actions/runs/43645591663
- v205: https://github.com/markoboskoauroville/mantra-manual-camera/actions/runs/43646590424 — release v205 is marked Latest.

## Sound Link (the Mac's sound into the camera), 15.11.2026 night

**Versions.** Mac: Sound Link (Mantra) v5 (repo SOUND_LINK, all runs green, installed and running). Camera: Sound Link code
in v206, v207, v209, v210, v212 and v213 (v212 installed on the Pixel; v213 built, waiting to be installed until the relay test is done).

**What works.** On the Mac: 1 SOURCE (BlackHole or the mixer, a pair of channels), 2 CAMERA, 3 SEND. The camera switches to
"Sound: MAC LINK · MacBook Pro · stereo" by itself. The meter, the take and NDI carry the Mac. The status line shows
"MAC LINK <buffer> ms", and in SONY MODE the details shrink so it is never cut. The Mac finds the Pixel on the Wi-Fi (Bonjour)
and over Tailscale (probe on UDP 47474). It takes the Wi-Fi first and falls back to Tailscale only when the Wi-Fi way stops answering.

**The buffer, before and after (Mac → Pixel, 2-minute runs).**

| | packets | lost | recovered | concealed | dropouts | buffer | round trip |
|---|---|---|---|---|---|---|---|
| Before, Tailscale relay (bridge, 23:44–23:48) | 39 751 | 14 | – | – | 10 | ~170 ms | 100–200 ms |
| Before, Wi-Fi (228 s, no redundancy) | 45 551 | 1 943 (4.3 %) | – | – | 37 | ~165 ms | 10–55 ms |
| After, Wi-Fi (v212 + Mac v5, 120 s) | 24 036 | 0 (13 in 289 s) | 8 | 5 | **0** | 155–183 ms | median 7.6 ms (4.6–90) |
| After, Tailscale (120 s) | 36 051 | 2 | 2 | 0 | **0** | 553–605 ms | median 114 ms (95–232) |

The Wi-Fi is clearly faster: total delay about 0.17 s, against about 0.64 s over Tailscale. The Pixel's Wi-Fi also changed
between the runs (−69 dBm on "mantra", then −54 dBm on "Something"), so part of the gain is the network.

**Not finished.**
1. GO LIVE does not carry the Mac yet. The WebRTC recorder is now wrapped (v212), but after the swap its microphone read
   gives 0 bytes ("AudioRecord.read failed: 0"), so the viewer got silence. v213 paces itself on the clock and hands the
   Mac's sound to WebRTC in that case. It must be tested on SANTOSHI RELAY and on VDO.Ninja.
2. Lip-sync: the picture is not yet delayed by the link's delay. A take with MAC LINK has its sound about 0.17 s late on
   the Wi-Fi (0.64 s over Tailscale); the number is on the status line. LESSONS.md, "Sound Link".
3. MONITOR (v210): the key beside the meter, only while MAC LINK. Tap: on/off; hold: 100/50/25 %. Not tried by ear (night).

**Marko in the morning.**
1. Mixer test: plug the XLR desk into the Mac. In Sound Link choose it in 1 SOURCE and the pair of the stereo bus
   (e.g. 1-2). Open Manual Camera and press 3 SEND. Check that the status line says MAC LINK and the meter moves.
2. Tap MONITOR on the phone (headphones in) and listen for clicks or gaps.
3. A short take, then check the sound in the file and say how late it feels.
