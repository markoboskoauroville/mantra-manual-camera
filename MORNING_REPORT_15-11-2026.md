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
