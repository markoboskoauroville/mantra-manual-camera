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

**Versions.** Mac: Sound Link (Mantra) v6 (repo SOUND_LINK, all runs green, installed). Camera: Sound Link code in v206–v215
(v215 installed on the Pixel, Latest).

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

**GO LIVE carries the Mac (v215, installed, 00:57).** Measured in Firefox with a WHEP client on the relay (the site's
own path, https://relay.ples-duse.org:8889/santoshi/whep):
- Mac sending: audio level 0.04–0.15.
- Mac stopped: the stream falls back to the phone's microphone, and packets keep flowing (quiet room 0.0003).
- SEND again: level back to 0.06–0.10 within a second.
- The same on VDO.Ninja: level 0.08–0.15, energy 0.22 after 18 s (it was 0 before the fix).
- How the swap works: WebRTC's AudioRecord is wrapped (live/MacAudioRecord.kt). Making the wrapper stopped the real recorder;
  v215 starts it again, so the microphone comes back without the Mac.
- v215 also allows only ONE MainActivity (singleTask). A second `am start` or the pairing link no longer opens a second
  camera that publishes over the first.

**Not finished.**
1. Lip-sync, v214 + Mac v6: the take's sound is stamped earlier by the link's delay (the Mac's ~9 ms + half the round trip
   + the buffer), computed ~173 ms on the Wi-Fi and ~626 ms over Tailscale. The stream's capture times are moved too, but
   that the viewer's A/V sync uses them is not proven. No clap test yet.
2. MONITOR (v210): the key beside the meter, only while MAC LINK. Tap: on/off; hold: 100/50/25 %. Not tried by ear (night).

**Marko in the morning.**
1. Mixer test: plug the XLR desk into the Mac. In Sound Link choose it in 1 SOURCE and the pair of the stereo bus
   (e.g. 1-2). Open Manual Camera and press 3 SEND. Check that the status line says MAC LINK and the meter moves.
2. Tap MONITOR on the phone (headphones in) and listen for clicks or gaps.
3. A short take with a clap in front of the Sony while the desk mic hears it, then check in the file that sound and picture line up.
4. GO LIVE with Sound Link on: the site should carry the desk's sound.

## Frankfurt relay (SANTOSHI RELAY), 15.11.2026 ~00:40

Marko, 14.11.2026 ~23:58: "I just want to send one stream, and then it is relayed somewhere ... Frankfurt delay, without YouTube branding".

**What exists now.** The phone sends ONE stream (WHIP) to MediaMTX on chromium-vm in Frankfurt (https://relay.ples-duse.org).
Every viewer of https://santoshi-band.pages.dev watches it there in the site's own player (WebRTC/WHEP, LL-HLS fallback).
The VM records each stream in 1-minute pieces and uploads them to Drive "Santoshi Band live (relay)/Santoshi live <date time>/Video",
with the two Google Docs "Chat" and "Stream statistics". VDO NINJA stays as the second choice. There is no YouTube.

**Proven tonight with the Sony (ILCE-7M5 over USB, v212 on the Pixel), 00:29–00:39:**
| | measured |
|---|---|
| Phone upload | one connection (trace: "relay offer made (one connection)", relay sessions: 1 publisher), about 4.2 Mbit/s H.264 + Opus stereo |
| Viewers | 8 at once: 4 WebRTC (Firefox on the Mac, including the site's banner) + 4 HLS readers on the VM |
| Picture at the viewer | 1920x1080, 23–26 fps (the Sony gives 25), 0 packets lost, 0 frames dropped, jitter buffer 140–180 ms |
| Delay | relay leg alone (VM test stream to the Mac) under 0.2 s (jitter buffer 32–63 ms). Sony to the viewer: estimated 0.4–0.6 s, not clocked (no clock in front of the Sony) |
| VM | MediaMTX 13 % of one core with 8 viewers, the VM 87 % idle; egress about 4.5 Mbit/s per WebRTC viewer (= the stream's bit rate) |
| Sound | Opus stereo reaches the relay, but tonight as digital silence (-91 dB): the source was Sound Link (the Mac), and the Mac played nothing. With the VM test stream the sound was correct (Opus 2 ch, level 0.09) |

The public live was stopped at 00:39:48. Nothing is on the site overnight.

**Not finished / known.**
1. The first seconds of a stream start at 640x360 and climb to 1080p (WebRTC's own bit rate ramp). The first recorded piece is small.
2. A second `am start` of the app opened a second camera screen, and the two published over each other (00:23–00:28). That is fixed by
   starting the app normally (one screen). Only adb did this, not Marko's hand.
3. Drive uploads go through rclone's PUBLIC Google client, whose global per-minute quota was used up at 00:47 (403), so the upload to
   Drive lags (18 of the night's pieces were on Drive at 00:45). The pieces wait on the VM for 7 days and the uploader keeps trying.
   The cure (optional, 5 minutes): an own Google OAuth client for rclone, then one consent on the VM.

**Marko in the morning, with the Sony.**
1. Sony on USB, Manual Camera open (v212 or newer), the Sony picture on screen.
2. Settings → STREAMING → **SANTOSHI RELAY** (it says "· default"). Settings close and LIVE starts. The LIVE key turns red and counts the viewers.
3. On another device open https://santoshi-band.pages.dev: the banner plays the Sony. Tap the speaker for sound.
4. To stop: hold LIVE for two seconds (or press SANTOSHI RELAY again). The recording is in Drive "Santoshi Band live (relay)".
