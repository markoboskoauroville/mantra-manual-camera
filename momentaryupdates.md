# Momentary updates: what Marko asked on 27.9.2026, 10:54, after testing v89 on his Pixel

Written before any code, so that when the quota runs out and he says **"continue"**, the next
session starts here. Each item has a status line; update it as the work moves.

His two screenshots (not in this public repo, they show his face) are on the Mac at
`~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/c96e4e97-image.png` (the camera, portrait,
lens L5 24mm, FOCUS FIXED, i.e. the selfie lens, 3264x1836 10-bit HEVC, ISO 41 on M, SHTR 1/65 on A,
HM) and `.../d9576ff5-image.png` (the take `mantra-20260927-105404` playing in the phone's own player:
the picture is turned wrong).

## The requests, in his words and what they mean

### 1. BUG: a take shot in portrait plays upside down (180° off)

> "When I'm recording into the portrait mode, actual orientation of the file, it's opposite 180
> degrees of what I was shooting. Look at the screenshot. So you need to write right orientation in
> the file so it's not upside down when I'm playing inside the player."

Seen on the **selfie lens** (L5, fixed focus). The rotation written into the MP4 (the orientation
hint / display matrix) is wrong by 180° for that case. Check: the front camera's sensor orientation
(270 on a Pixel, the back is 90), the front camera's mirror, and whether the phone's rotation is added
or subtracted for front vs back. Must be right for every lens, both orientations, and both ways of
holding landscape. Test: record on the emulator with the front and back cameras in portrait and
landscape and read the rotation out of each file (`ffprobe` side data / `rotate` tag).

**Status:** done in **v90** (27.9.2026). `Mechanism.recordingRotation` → `MediaMuxer.setOrientationHint`.
Emulator: portrait take 90° (upright), landscape (display 270) 180°, both read back with ffprobe and
matching the monitor. The selfie lens is proven by unit tests only (the emulator has no front
camera): **his Pixel is the test**.

### 2. Shutter: presets in degrees under the fader

> "After the shutter, you need to put buttons. Slider stays, but now we have presets in degrees.
> 0 degrees, 90 degrees, 180 degrees, 270 degrees, and 360 degrees. And these presets are nudging
> the slider of shutter speed to the right shutter speed."

A row of five keys under the SHTR fader: **0° 90° 180° 270° 360°**. Shutter angle → exposure time =
angle / 360 × 1 / fps (at 25 fps: 90° = 1/100, 180° = 1/50, 270° = 1/33, 360° = 1/25). The fader
stays and moves to the value. A preset puts shutter on M. **0°** has no exposure time of its own
(zero light): it is read as the shortest shutter the sensor allows. Anything the sensor or the frame
rate cannot honour is clamped, and the fader shows where it landed.

**Status:** to do, v90.

### 3. White balance: A is a one-shot measurement, nothing is remembered

> "You should not remember my white balance, manual one, after I set to auto. So when I press auto,
> my slider will jump to the automatic position and then go back to the manual. I don't want to
> track white balance, this feature I don't need. When I press auto, it will just stay auto for a
> few seconds until it finds the white balance from the camera, and then it will switch back to
> manual automatically."

WB's A is no longer a mode. Pressing A: the camera's AWB runs for a few seconds until it settles,
the fader follows to that Kelvin, then WB goes back to **M** by itself and stays there. No stored
manual value comes back afterwards, and nothing tracks the light continuously. In AUTO mode (the M
key) WB behaves the same way: measured once, then held.

**Status:** done in **v90**. WB left the M key's AUTO/HM/FM count; the camera measures once on
opening; the probe waits for AWB converged (20 frames minimum, 90 maximum, 6 s deadline). Emulator:
started → 5003 K → M in 0.65 s.

### 4. ISO: presets at the camera's base ISO (and a second native ISO where there is one)

> "Under ISO, I want presets based on base ISO of the camera in question. So this is the point in
> camera which is the least noise. So if this, for this camera is 200 or 100, just give me preset.
> Maybe it has two. There are two cameras which has dual native ISO. And then I can just jump from
> highlight to low light."

Keys under the ISO fader, per lens. What Android publishes: `SENSOR_INFO_SENSITIVITY_RANGE` (its
lower end is the sensor's base, the cleanest ISO) and `SENSOR_MAX_ANALOG_SENSITIVITY` (above it the
gain is digital, more noise and nothing gained). Android does **not** publish a dual native ISO, so
the keys are **BASE** (the range's lower end) and **HIGH** (the top of analog gain: the low-light
preset), each showing its number. Say this truthfully to him. A preset puts ISO on M.

**Status:** done in **v90**. Emulator: BASE 100, HIGH 1600; BASE lit, ISO on M.

### 5. His general direction

> "We are bringing more features which are professional to this app. Please update with the
> requested features and fix the bugs."

## The plan after this

- **v90**: items 1–4 above.
- **v91**: the player inside the app (was v90), then scopes v92, settings with a floating preview
  v93, the GPU stage v94, log-aware auto exposure v95, the grey-card WB sweep v96, the Mac webcam v97,
  MANTRA_KELVIN v98, tracking focus v99. The phase document holds the details.

## Seen in his screenshots, not asked (for later)

- The status line at the top is unreadable over a bright sky (grey on white), and so is IRIS.
- He was on L5 and the M key said HM with ISO on M: half manual works on his Pixel.
- The recording drive shows 78.1 GB / 3:40 h: the storage key works on his phone.

---

# Second message, 27.9.2026, about 11:10: output routing, professional controls, core fixes

His text, kept whole so nothing is lost:

> **Feature Request: Output Routing, Professional Controls, and Core Fixes**
>
> **1. Previous Updates Integration.** Please read the 'momentary_updates.md' file. Implement all
> updates and architectural changes listed in that document before proceeding with the new features
> below.
>
> **2. Unified Output Routing and Settings Switchboard.** We are streamlining the application's output
> engine to support four distinct, parallel destinations. To declutter the main user interface,
> destination-specific configurations are moving into the Settings menu. The four supported output
> destinations are Stream to File (Local Recording), Stream over USB (OBS Camera), Stream via NDI, and
> Stream to YouTube. The Settings view will act as a routing switchboard featuring independent,
> collapsible activation switches for each destination. When a switch is turned on, its specific
> configuration panel expands. For NDI, this includes options to select between Full NDI and NDI HX.
> For the new YouTube channel, the expanded panel allows the user to enter all necessary stream
> details and authenticate directly from the application. When a destination is disabled, its
> settings remain hidden to keep the interface clean. The previous NDI toggle on the main UI must be
> removed entirely and integrated into this new switchboard.
>
> **3. Master Action Trigger and Telemetry.** Activating a destination in the Settings menu only arms
> that specific channel. Streaming or recording to any configured destination will not commence until
> the user presses the master Record button on the main interface. The user must be able to mix and
> match any simultaneous combination of the four destinations. For example, a user can arm YouTube and
> USB streaming while leaving Local Recording disabled, and both armed streams will start perfectly
> synchronized upon pressing Record. A new streaming status line must be integrated at the top of the
> main camera view. This telemetry overlay will provide real-time feedback on how the application is
> sending images, clearly indicating which of the four destinations are currently active and
> receiving data once the master Record button is engaged.
>
> **4. Professional Exposure Controls: Shutter and ISO Presets.** To enhance professional usability,
> we are introducing discrete preset buttons beneath the existing faders. For the Shutter, add a row
> of five preset buttons in degrees: 0°, 90°, 180°, 270°, and 360°. Selecting a preset will calculate
> the correct exposure time based on the current frame rate (e.g., 180° at 25fps sets the shutter to
> 1/50) and smoothly nudge the fader to that exact value, instantly switching the shutter to Manual
> (M) mode. The 0° preset will snap to the absolute shortest exposure time the sensor allows. Anything
> the sensor or frame rate cannot honor will be clamped, with the fader reflecting the final value.
> For ISO, add preset buttons beneath the fader based on the specific camera's hardware capabilities.
> Since the Android API does not explicitly publish dual native ISO, the presets will be labeled BASE
> (pulling the lowest clean ISO from the sensor's sensitivity range) and HIGH (pulling the maximum
> analog sensitivity before digital gain is applied). Selecting either will lock the ISO into Manual
> (M) mode and display its corresponding number.
>
> **5. One-Shot White Balance Measurement.** The White Balance Auto (A) mode is being redesigned into a
> one-shot measurement tool rather than a continuous tracking feature. When the user presses Auto, the
> camera's automatic white balance will run for a few seconds to evaluate the scene. Once it settles
> on a Kelvin value, the fader will jump to match that value, and the system will automatically lock
> back into Manual (M) mode. It will no longer remember or revert to previous manual settings, nor
> will it continuously track color temperature changes.
>
> **6. Critical Bug Fix: Portrait Orientation Matrix.** Recordings captured in portrait mode using the
> front-facing (selfie/L5) camera are currently playing upside down (180 degrees off) in standard
> media players. The application must accurately calculate and write the correct orientation hint
> (display matrix) into the MP4 file's metadata. This calculation must account for the specific
> sensor orientation (which differs between front and back cameras) and mirror configurations to
> ensure right-side-up playback across all lenses and device holding positions.
>
> **7. App Icon Update.** Update the application icon by modifying the existing asset. Do not create a
> new design or replace the base icon. Keep the original design exactly as it is, but add a distinct
> orange outline (using Mantra's UI color palette, e.g., #ff9800) strictly around two elements: the
> outer edge of the main camera body; the outer edge of the inner lens circle. Apply these stroke
> modifications directly to the existing vector/SVG files in the project repository.

## How it is split into versions (whole numbers, one confirmed step at a time)

| Version | What | Status |
|---|---|---|
| **v90** | Items 4, 5, 6, 7 (and 1–4 of the first message, which are the same things): shutter-angle presets, BASE / HIGH ISO presets, one-shot WB, the orientation written right into the MP4, the icon with the orange outlines | **released 27.9.2026, tested on the emulator** (monkey 90091 20,000 clean). One display fault (M key after a preset) fixed for v91 |
| **v91** | The switchboard in settings (File, USB, NDI, YouTube, each a switch that expands its panel; NDI HX / Full inside it); the NDI key leaves the main screen; the record key becomes the master trigger for every armed destination; a telemetry line at the top naming what is being sent. File and NDI wired first | **released 27.9.2026, tested on the emulator in portrait**: switchboard and panels; FILE + NDI HX started in one call and stopped together (118 frames saved); NDI alone; nothing armed says so; telemetry dim / white / red dot with numbers; monkey 91091 20,000 clean. FILE is armed by default so the key still records after the upgrade. Found and fixed for the next build: the status line said 0.0 fps when idle, and it now sits on a dark band too |
| **v92** | **USB (OBS camera)**: the picture to OBS on the Mac over the USB cable. Open question for him: which route OBS should receive (NDI over the USB tether already works; a plain SRT/RTMP feed into OBS's Media Source needs no plug-in; a true "OBS camera" source is the Mac companion of the old v97 plan) | to do, ask |
| **v93** | **YouTube**: RTMPS to YouTube's ingest with the stream key; "authenticate from the app" = Google sign-in with the YouTube Data API to create the broadcast and fetch the key. That needs a Google Cloud OAuth client for the app, which Marko has to create (a step for him) | to do, needs his OAuth client |
| v94 … | the in-app player, scopes, settings preview, the GPU stage, log AE, grey-card WB, KELVIN, tracking (the phase document, each moved on by the new versions) | later |

**Status of this second message:** saved 27.9.2026; v90 and v91 released the same day. **Next:
v92 USB and v93 YouTube, both waiting on a choice of his** (see the table): which route USB takes to
OBS, and whether YouTube starts with the stream key alone (no Google sign-in, works at once) or waits
for a Google Cloud OAuth client he creates for the sign-in.

**The icon (item 7):** the v88 drawing unchanged, plus two #FF9800 strokes, 2 units wide, one unit
inside the body's outer edge and the lens circle's outer edge (r 10.5), so the outline's outer side
is exactly the old edge and the symbol stays in the 42 box (`res/drawable/ic_launcher_foreground.xml`).
"Inner lens circle" was read as the lens (the dark ring's outer edge), not the small white glass.

## His answers, 27.9.2026 afternoon (after v91)

- **USB (OBS camera): the Mac companion app.** A small Mac app receives the phone over the USB cable
  and appears as a camera in OBS, Zoom and everything else (the old webcam plan; research in
  `NDI_LICENSING_AND_WEBCAM.md`). The biggest of the four; its own repository.
- **YouTube: the stream key first.** He pastes the key from YouTube Studio into the YouTube panel once;
  the record key goes live. Google sign-in can come later.

**Order, therefore (renumbered: v92 and v93 went to his icon corrections):** **v94 = YouTube by stream key** (all on the phone: an RTMPS publisher fed by the
same encoder as the file and NDI, with sound); **then USB, the phone's side of the Mac companion**
(and the companion app in its own repository) is **v95**. Swapped from the first split because YouTube is
phone-only and finishes first; the Mac app is the longer road.

## Third message, 27.9.2026, about 15:40 (after v92's icon)

1. **"Camera body should be gray. That's the rule of the icon."** Keep the drawing, the orange lens
   ring and the thin orange body outline; the body itself is grey, as on his launcher (light grey on
   the dark ground). **Status:** done in **v93** (#B4BABF).
2. **White balance, three screenshots from his Pixel** (L2 25 mm, ISO 50, 1/682, HLG 10-bit, 4K,
   outdoors in afternoon daylight, WB on M): the fader at the far right (value hidden under the
   screenshot thumbnail), at **4100 K**, and at **3200 K**. *"Let me know what has changed in the white
   balance in these images after I'm moving the slider."* Screenshots on the Mac at
   `~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/573d21fa-image.png`, `e4713b3d-image.png`,
   `aa18b794-image.png`. **Status: analysed 27.9.2026, a fault found, the fix needs his trace.**

   **What the pictures measure** (mean colour of the pavement, bottom of each frame; G = 1):
   | fader | whites R/G, B/G | mid-tones R/G, B/G | darks R/G, B/G |
   |---|---|---|---|
   | far right (6500 K; the number was under the thumbnail) | 1.00, 1.01 | 0.97, 1.06 | 0.90, 1.15 |
   | 4100 K | 1.00, 1.00 | 1.00, 0.98 | 0.96, 0.99 |
   | 3200 K | 1.01, 1.00 | 1.04, 0.99 | 1.03, 1.06 |
   Moving the fader **right made the picture a little bluer, left a little warmer**, a few percent at
   most, with a green-cyan lean at 4100 K. At the far right the top-left corner went **red-magenta**
   (R/G 1.91): a colour-shading fault at the edge of the lens.

   **What it should do** (our own maths, run on a white object in 5600 K daylight): 3200 K → strongly
   blue (B/G 1.72), 4100 K → blue (1.33), 5600 K → neutral, 6500 K → slightly warm (R/G 1.08). The
   convention of every camera: the number is the light you are correcting for, so tungsten on a
   daylight scene turns it blue. **Our gains are right; the Pixel is not putting them on the
   picture** in this mode (10-bit HLG, 4K). What little moved is most likely the colour matrix half
   alone, which moves the other way. The trace's `white balance readback` line (the gains and the
   correction mode the camera says it used) settles it: export the trace (settings → trace) after
   moving WB, or plug the phone in so the session can read it.
3. **"From now on, under the camera, write the version number in the icon, so in the icon I can already
   see what my version is."** A rule for every build: the build generates the icon with `vNN` painted
   under the camera from `appVersion`. **Status:** done in **v93** (and grey body, item 1): the icon is
   a template in `app/src/main/icon`, `build.gradle.kts` draws the number; seen on the emulator's
   launcher as "v93".

## Fourth message, 27.9.2026, evening

> "Please build the next version and write inside the settings when there is a version number. It
> is basically a hidden link. If I click on the version number, it will take me to the GitHub latest
> release APK page."

The version number in settings (top right, and at the bottom) opens the repository's latest release
page on GitHub, where the APK is. **Status:** done in **v94**: the number at the top right of settings
opens `https://github.com/markoboskoauroville/mantra-ndi/releases/latest`; proven on the emulator (a
tap started Chrome on that address). YouTube moves to v96.

## Fifth message, 27.9.2026, evening: the icon, literally

> "For the next icon and default icon, literally vectorize this image. Literally make the same camera
> shape. Literally make the same body color and just add a thin orange outline around the camera body
> and fill up this space between lens and camera with orange color. Literally."

His picture: `~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/f8c7d4c6-image.png` (the launcher's
themed rendering of our icon: a grey camera on a dark slate circle). To do: trace it — the same body
shape, the same grey, the same background colour — then a thin orange outline around the body, and the
dark ring between the lens glass and the body filled orange. The version number stays under the camera
(his earlier rule). **Status:** done in **v95**: traced against the 72-unit circle (the circle is 160
px in his picture): flash tab, chamfered corner, hump x 48.8–59.6 up to y 39.8, body x 36.7–71.8 y
42.5–66.8, lens centre (54.2, 55.1) ring r 6.7–9.6, grey #A8ADAF on slate #263238; orange ring, a
1-unit orange outline inside the body's edge; lifted 4 units for the number. Seen on the emulator's
launcher reading v95. Not yet done: the SNAP key on the rail still draws the v88 camera; it should
become this one.

## Sixth message, 27.9.2026, evening: the white balance trace

> "Here is the trace from the Google Pixel 7 phone and it doesn't work. Interestingly enough, on my
> Nothing Phone 2a, the white balance slider is working. Please diagnose what is the issue and what are
> specifics of Pixel Phone 7."

Trace: `~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/93abab3b-trace-2026-09-27-164207.txt`.
**Status: diagnosed; v96 released with the fix, to be proven on his Pixel.**

**What the trace shows.** Pixel 7, Android 16, v95, lens L1 = logical camera 0, physical lens 3 (the
ultra wide), 3840x2160 10-bit HEVC. The camera **accepted** manual white balance (readback: AWB mode 0
= off, correction mode 0 = transform matrix), but the gains it reported hardly moved and moved the
wrong way: asked 5812 K → red 2.340 / blue 1.645; 4768 K → 2.389 / 1.499; 3292 K → 2.328 / 1.487. For
3292 K our maths sends about red 1.44 / blue 2.34 (daylight turns blue). The probe at start also
clamped at 6500 K: the auto gains it anchored to did not fit this lens's calibration.

**What is special about the Pixel 7.** Its lenses are *physical sub-cameras* of one logical camera
(`[0:3]`, `[0:4]`, `[1:5]` in the trace; only the "fused" L2 and L5 are the logical cameras
themselves). The app routes the picture to the physical lens (`setPhysicalCameraId`), but every
colour key was set on the **logical** request only, and the anchor and readback were read from the
**logical** result, which can describe another sensor. A sub-camera keeps its own colour pipeline and
only reads keys set on it with `setPhysicalCameraKey`. The Nothing Phone (2a) exposes each lens as its
own camera, so a plain request reaches it, which is why the fader works there.

**v96:** the request is built naming the physical lens, every white balance key is set on the lens
too, the anchor / probe / readback come from the lens's own result, the gains sent are traced in full,
and eight frames later they are compared with what the lens used. If a lens still ignores them (the
fused logical camera in 10-bit HLG may be that case: his screenshots were on L2), the screen says so
and the fader switches to the camera's own presets instead of moving a number and not the picture.
Emulator: the check says "followed", no false alarm; monkey 96096 20,000 clean. **His test:** L1, L2
and L3 on the Pixel, WB from 3200 K to 6500 K; the trace's `white balance check` lines say which lens
followed.

## Seventh message, 27.9.2026, evening: "GPU stage and log exposure"

Asked "what is next upgrade" (answer: YouTube by stream key), he chose instead: **the GPU stage and log
exposure**. Order now: **v97 the GPU stage** (phase 4 of the phase document), **v98 automatic exposure
that knows the log curve** (phase 5); YouTube, the Mac companion and the rest move after them.
**Status:** **v97 released** (the GPU stage): `GpuStage.kt`; the take has its own encoder and bit rate,
the stream its own (settings: Picture path GPU STAGE / DIRECT, stream bit rate). Every output carries the
raw sensor orientation, so rotation is unchanged. 10-bit through the GPU only with GL_EXT_YUV_target +
RGBA1010102 + BT.2020 HLG surfaces (YUV read as numbers, Media3's matrix), else the direct path, traced.
Emulator (8-bit only): picture, take, FILE + NDI HX, monkey clean. CPU on the emulator 29 % vs 18 % direct
(emulated GL, two encoders) — **his Pixel gives the real figure; if the 10-bit colours look wrong there,
Settings → Picture path → DIRECT.** Not moved to the GPU yet, and why: the full-NDI YUV packing (it still
comes from its own camera reader), the focus and brightness samples (tiny reads of the preview). v98 next.

## Eighth message, 27.9.2026, 18:00: manual controls only work on L2

> "On pixel phone, manual controls now in GPU mode only works with lens 2. Other lenses no or little
> bit, I'm not sure, but there are not big changes at all."

His screenshots (v97): L2 25 mm FM, ISO 1013, 1/316, focus 0.18 m, WB 4600 K — works; L1 17 mm FM, ISO
1604, 1/1286 — the picture hardly answers. Files: `~/.claude/uploads/e6c861f6-db96-40aa-8b9c-c88d869cc059/`
`59af1afb-image.png`, `2e73f372-image.png`. **Diagnosis:** L2 (and L5) are the Pixel's logical cameras;
every other lens is a physical sub-camera, and v96 moved only the white balance keys onto the lens.
ISO, shutter, frame duration, focus, AE / AF modes and the tone curve were still set on the logical
request only. **Status:** fixed in v98 (with log exposure): every key the lens accepts per lens is
copied onto it at each request.

**v98 released, 27.9.2026 18:10:** (1) every key the camera lists as per-lens is copied onto the
physical lens at every request (ISO, shutter, frame duration, focus, AE / AF modes, tone curve, white
balance), and ISO / shutter / focus are read back from the lens; (2) **log-aware auto exposure**: while
exposure is automatic on a log curve, the focus box is read as a grey card twice a second and exposure
compensation steers grey onto the maker's number (S-Log3 41 %, V-Log 42 %, LogC3 39 %, LogC4 28 %, Film
Gen5 38 %), never hunting (one step only when it brings grey closer, step learnt from the last move);
the status line reads e.g. `grey 39% · S-Log3 41%`. Emulator: loop settled in one move at 39 % vs 41 %,
monkey clean. **Open on his phone:** the tone curve treats its input as display-referred; Camera2 says
linear. If grey sits far below target with compensation at its limit, that is the answer, and the curve
gets fixed next. **His test:** L1, L3, L4 in FM — ISO and shutter must now move the picture; the trace
line "lens N takes its own: …" lists what each lens accepts.


## Ninth message, 27.9.2026, evening: "let's develop a tracking focus"

Asked what is next (YouTube was proposed), he chose **tracking focus** (Phase 9 of the phase document).
**Status: v99 released, 27.9.2026 19:45.** AF key: AF → TRK → MF. In TRK the pinched focus box is the
pattern, a thin outer square the search zone; a tap sets it. GPU stage: luma copy (256 wide) of each
frame, NCC per search place in a fragment shader against a kept reference frame, scores (≤ 64×64) read
back, sub-pixel peak. The mark follows by the set fraction, refocuses past the tolerance, and turns red
with LOST below the confidence (stays put). Settings: search zone ×, frames between searches, confidence,
tolerance, follow speed. Tests: known shift to ⅓ px, exposure change + noise, flat / unrelated = lost,
screen↔sensor exact inverse. Emulator: LOST on a flat wall (correct), 1.000 on a window, monkey clean;
its scene does not move, so **following a moving subject is for his phone.** Found and fixed: the pattern
copy (glCopyTexSubImage2D) came back flat (LESSONS 9). Also fixed on the way: the AF region mapped the
screen through "front camera" instead of what the monitor shows (they differ since the GPU stage).
Needs the GPU stage (Settings → Picture path).

## 2.10.2026, 02:20 — white balance, calibrated against the G815's LEDs (asked in the G815_LIGHTS session)

> Also, I'm using my own app. This is my own camera NDI camera. If colors don't match, you need to also update my camera because white balancing is not functioning now. We can use opportunity with these LED lights which are the colors of the light pure spectrum to also calibrate my camera so it gives the true colors. Now white balance is a bit off, it's almost there but it's bit off. So fix my camera and fix my keyboard application.

- The camera looks down on the Logitech G815; the keyboard can show known pure colours per key (G815_LIGHTS).
  Use them as a reference to measure and correct the white balance. — **Status:** open (after the keyboard app)

> (2.10.2026, 02:55) Please also fix my NDI camera white balance algorithm using this keyboard so it needs to recognize the right colors. Please fix my app camera app, it's in repository and we have here our tester which is an emulator of Android. Now we can do it all here. Please let's fix it.

- MEASURED 2.10.2026 through scrcpy of his Pixel 7 (the camera looking down on the G815): keys sent magenta
  (ff00ff) show BLUE, white shows bluish, orange (ff6000) shows red, yellow shows olive. To his own eye the keys
  are right (magenta, white, orange), so the error is the camera's: too much blue, too little green/red balance.
  — **Status:** started 2.10.2026

> (2.10.2026, 03:10) now instead of using an emulator, you can use my real Pixel phone. It's now in debugging mode, and we're going to fix the camera. You can do anything through my screen copy because it's a full-featured touchscreen. Make the camera use the right white balance first. Test is with the keyboard, and then I give you precise light with precise colors, and we're going to make this white balance perfect

- The real Pixel 7 over adb + scrcpy, not the emulator. First the right white balance, tested against the G815;
  then Marko gives a precise light with precise colours and it is made perfect. — **Status:** started
- Diagnosis (read-only study, 2.10.2026): "A" is the Pixel's whole-frame AWB (no region of its own,
  CONTROL_AWB_REGIONS never set) and it neutralises the warm room lamp, so the self-lit LEDs read blue;
  anchorKelvin clamps at 3200 K (WhiteBalance.kt:48-49); LEDs over-exposed so red clips and CCM crosstalk
  eats orange's green. Plan: A becomes a spot white balance on the focus box (sampleGreyCard,
  MainActivity.kt:1331, to return mean R G B, linearised, clipped pixels rejected; gains iterated until
  R=G=B in the box), Kelvin range widened to ~2500-10000 K.

- **v100 (2.10.2026):** A with the focus box out = the camera's own measurement, then THE SPOT: the box's lit,
  unclipped pixels read in linear light, red and blue gain moved by G/R and G/B, up to 5 rounds until the box
  is neutral (±2 %); the result becomes the fader's anchor. With the box put away, A is as before.
  — **Status:** built, unit-tested (3 new tests); to be tried on his Pixel against a white G815 key

## 2.10.2026, 03:45 — exposure circle, white balance rectangle, flashlight, settings reorganised

> And also you need to build new features, which is a circle in the image, which is actually point of exposure, automatic exposure. So I can limit circle is exposure and the square is focus, as is it now. And the size of both of them, default size, can be set in settings. And also at the top of the settings, put one icon with the flashlight, and that icon is turning on the flash. I don't want it to take over my user interface, but under settings can be the first thing. So the first thing important in the settings are something I'm using all the time, and that's the settings of the first focus size, then this round exposure window, and then light. After that we have 8-bit/10-bit switch, and after that we have a resolution. And we need to remove interface orientation from the settings. It, it can be always at 0, so we can remove that part. And let's reorganize the settings and add the new features after white balance is done.Also, to the white balance, we need to add a 3rd rectangle, and that's the white balance rectangle. So I can point that rectangle or anything gray or white in the scene, tap on it, and it will, it will do white balancing.

1. White balance first (v100 = the spot on the focus box; being tested). — **Status:** in test
2. A THIRD shape, the WHITE BALANCE RECTANGLE: point it at anything grey or white, tap it, and it white-balances
   on what is inside it (replaces v100's use of the focus box). — **Status:** open, next
3. A CIRCLE = the exposure point (auto exposure meters inside it); the square stays focus. — **Status:** open
4. Settings: default size of the focus square and of the exposure circle. — **Status:** open
5. Settings, at the very top: a flashlight icon that turns the torch on (not on the main screen). — **Status:** open
6. Settings order: focus size, exposure circle size, light; then 8-bit/10-bit; then resolution. — **Status:** open
7. Remove "interface orientation" from settings; always 0. — **Status:** open

## 2.10.2026, 03:55 — three shapes, three functions, and their keys on the left

> But since I want to distinguish the shapes on the screen, we should be very different. So we have the same as visual languages Android phone. So the square is circle will be the exposure and triangle will be the white balance. So we have 3 different shapes in the in the, in this app for the 3 different functions. And since they are so important, you need to put their, their shapes, icons, on the left or right side of the user interface. I would say left side, left side. And they will have 2 states. Uh, one state is grayed out, it means it's not showing on the screen. Second state is white, it means it's shown on the screen. And third state is orange, which means the pinch action is changing the size of that one. So this orange is toggle, so only one I can change with pinching action, only one of those guys. And same for focus, for white balance. And the gray balance or white balance action is tapping. So when I tap, it reads and it locks. The whole app doesn't have any tracking at all. It just— user needs to tap, it reads, it probes the right value, and then it stays there. And the same visual language as for focusing. While searching, it's orange. When it's locked, then it's green.

8. Three shapes: SQUARE = focus, CIRCLE = exposure, TRIANGLE = white balance (replaces item 2's rectangle). — **Status:** v101 = the triangle; the circle with item 3
9. Their three icons on the LEFT rail. Grey = not on the picture; white = on the picture; orange = the one a
   pinch resizes (only one at a time, a toggle). — **Status:** open (v102)
10. White balance is a tap: it reads, probes, LOCKS and stays — nothing tracks. Orange while searching, green
    when locked, as the focus square. — **Status:** v101

## 2.10.2026, 04:05 — a feature that freezes another phone says "not available" and goes back

> Also, one more time, one more thing, because this app is compatible with Pixel 7 mostly, and on other phones it can freeze when using some features. And map should detect freezing and say just to user feature not supported and not freeze. So we have this issue on Nothing Phone 2A when I change to low curves, then it freezes. I think we solved this problem, but check the MDs, what was with that. And if we, if we can apply this feature instead of freezing the screen, just to say this feature is not available and go back to the old settings.

11. A freeze watchdog: when a change (e.g. a log curve on the Nothing Phone 2a) stops the picture, say "this
    feature is not available on this phone" and go back to the settings before it. Check the MDs for what was
    done about the Nothing Phone's log-curve freeze. — **Status:** open (after the white balance test)

## 2.10.2026, 04:15 — one L key for the lenses

> This lenses chooser is taking too much space, so we should simply— L, only one icon. When user press L, they uncollapse. User choose one lens and then it collapse again. So then we have space for our square and triangle.

12. The lens keys L1..L5 fold into ONE key, L: a press unfolds them, choosing a lens folds them again; the room
    goes to the square, circle and triangle keys. — **Status:** open (with the rail keys, item 9)

- **MEASURED v102 on his Pixel, 2.10.2026 03:51** (keyboard all white, triangle on it, one tap):
  r/g 1.724 → 1.372 → 0.947 → 1.009, b/g 0.764 → 0.897 → 1.004 → 1.011, "locked: neutral after 3 steps".
  Pure colours afterwards through the camera: red, green, cyan, blue right; orange pale (legends clipped),
  yellow leans green, magenta violet. Next: his precise light, and exposure for the LEDs. — **Status:** item 1
  and 10 done for the white; colour accuracy of saturated colours open
- **Re-measured 03:52 with truly white keys** (the first run had his orange M2 bank under the triangle — he had
  switched banks): locked neutral after 6 steps (r/g 1.018, b/g 0.998). Through the camera then: red, orange,
  yellow, green, cyan, blue, magenta and white all read as themselves. — **Status: white balance DONE.**
- **v103 (2.10.2026):** the three marks — square focus, CIRCLE exposure (tap: meters there, locks; orange then
  green), TRIANGLE white balance — and their keys on the left rail drawn as the marks: grey off the picture,
  white on it, orange = the one a pinch resizes; a tap steps grey → white → orange → grey. One L key (whisper:
  the lens in use) opens the lens keys and choosing one closes them. Items 3, 8, 9, 12. — **Status:** built
- **v104 (2.10.2026):** settings start with the light (a torch icon at the top: grey off, green on, kept on
  while the camera runs), then the sizes of the focus square, the exposure circle and the triangle, then
  8-bit/10-bit and resolution; "Turn the preview" removed, the turn always 0. The exposure circle waits at least
  6 frames before it believes "settled" (v103 locked after 1). THE FREEZE WATCH FOR EVERYTHING: the request that
  last made a frame is kept; frames stopping 1.2 s after any change put it back and the screen says "That
  setting is not available on this phone — put back as it was" (items 4, 5, 6, 7, 11). Not provable on the
  Pixel (nothing freezes there); the curve's own v83 watch, proven on the Nothing, stays. — **Status:** built

## 2.10.2026, 04:22 — a tap anywhere is for the armed shape, also in full screen

> So when we are talking about red triangle, red square, and red circle, the one which is in pinch action, also in the full screen when I remove everything, the tap still works. So tapping is always 2 actions: it moves the shape to the position and do automatic correction and then locks.

13. The armed shape (orange key — "red" in his words — the one the pinch resizes) is also the one a tap on the
    picture serves: the tap MOVES that shape to where it landed, runs its automatic correction (focus, exposure
    or white balance) and LOCKS. The same in FULL screen, where nothing is drawn. — **Status:** v105
- **v105 (2.10.2026):** a tap anywhere on the picture serves the armed (orange) shape — square focus, circle exposure, triangle white balance — moving it there, correcting, locking; in the clean feed and under CTRL too (item 13). — **Status:** built

## 2.10.2026, 04:30 — hide the outputs line; the status line names the armed shape and says where it locked

> Also, you need to give me toggle in the settings to hide the streaming status display, which is USB, NDI, file, and so on. And always when the active triangle is there— triangle, square, circle— at the top status line, you need to write exposure circle. white balance triangle, focusing square, and then when it reads the value and locks the shape in the top status, you need to write exact number where is it locked, on which focus distance, what is the white balance color, and what is the exposure level.

> after everything is done, please remind me to plug into the screen copy my notiphone 2a so you can freeze it for sure with some functions

14. A settings switch that hides the outputs line (FILE USB NDI YT). — **Status:** v106
15. The top status line names the armed shape ("focusing square", "exposure circle", "white balance triangle")
    and, when it locks, the exact value: focus distance, white balance (K and gains), exposure (ISO, shutter, EV).
    — **Status:** v106
16. At the end: remind Marko to plug the Nothing Phone 2a into the screen copy, to test the freeze watch on a
    phone that really freezes. — **Status:** reminder pending
- **v106 (2.10.2026):** settings switch SHOW ON THE CAMERA for the outputs line; the status line starts with the armed shape (FOCUSING SQUARE / EXPOSURE CIRCLE / WHITE BALANCE TRIANGLE) and, once locked, its numbers: focus distance in m and dioptres, ISO + shutter + EV, K + gains (items 14, 15). — **Status:** built

## 2.10.2026, 04:50 — the rail keys wear the colour of their shape

> please add another feature so the side buttons of circle square, triangle should follow the colors on the screen. They should be in sync with the same icons. Of course, when it's off, it's grayed out, so let them be in sync

17. The □ ○ △ keys take the colour their shape has on the picture at that moment (white idle, orange
    searching, green locked, red failed); grey when the shape is off. The armed one (the pinch's and the tap's)
    is then told by a bar under its key, since orange now means "searching". — **Status:** v107
- **v107 (2.10.2026):** the □ ○ △ keys wear their shape's colour of the moment (white, orange searching, green locked, red failed), grey when off; a bar under the armed one (item 17). — **Status:** proven on his Pixel 04:44 (white → orange with the bar → green; the circle locked after 34 frames)

## 2.10.2026, 04:55 — letters inside the shapes

> you need to write mnemonics inside the shapes. So first square is F, then the second circle is E, and third one triangle is W

18. A letter inside each mark key — PROVEN v108 on his Pixel 04:58: F in the square (focus), E in the circle (exposure), W in the triangle (white
    balance), in the key's own colour. — **Status:** v108 (the rail keys; the shapes on the picture stay empty so
    they cover nothing of the subject, unless he asks)

## 2.10.2026, 05:00 — the triangle filled instead of W

> w doesn't look good because it's smaller. Instead of w, just put fill the triangle inside with white colorOr

19. No W: the triangle key is a FILLED triangle (white when idle; it keeps following its shape's colour —
    orange searching, green locked, grey off — so the keys stay in sync, item 17). F and E stay. — **Status:** proven v109 on his Pixel 05:05

> (05:08) result is not good. The idea is that the triangle has a fill which is white, but the outline changes its color between green, white, and yellow. Now the whole triangle is green. What's going on

20. Correction of 19: the triangle's FILL is always white; only its OUTLINE follows the shape's colour (white,
    orange, green, red). Off: grey outline and a grey fill. — **Status:** v110
    — v110 PROVEN 05:12 on his Pixel: white inside, outline orange while reading, green locked.

## 2.10.2026, 05:12 — the torch, and the top of settings as one line of toggles

> so the torch is actually not working, and in the torch line next to the settings we need to add the basic parameters of the camera. That's the frame rate, bit depth, resolution. Right there at the top, and all buttons now will be toggle. So we need only one button per setting, and focus square, exposure circle, and white balance triangle. I am missing the icon behind each on the left side of each, and everything can fit in one line because the size will be a drop-down menu. You go by 25% that's the scale. Every 25% you add value inside the drop-down menu

21. The torch does not work for him: find out why and fix. — **Status:** investigating
22. Settings' top line, beside the torch: FRAME RATE, BIT DEPTH, RESOLUTION, each ONE toggle button (a tap steps
    to the next value). — **Status:** v111
23. Focus square, exposure circle, white balance triangle: their shape icon on the left of each, all three in ONE
    line, the size a drop-down in steps of 25 % (25, 50, 75, 100). — **Status:** v111

## 2.10.2026, 05:15 — settings as quick toggles above tabs; the status line takes the free space

> when my outputs are gone, the status line has a gap. Top status line, it needs to move always aligning to or using the free space available to go to the edge of the screen also, we need to better organize the settings in sections and name sections accordingly. This settings title is not needed in settings. We can use this real estate more, so we remove this text and see the sections. For example, when we use organize it better, simply let's say for example display. So on the display, we can have picking, we can have hiding the streaming destinations. Anything that was shown on display should be under display. Then camera settings, all related to the camera. So the idea is in the area where there is this torch and when there is this settings text which is going to be deleted, what I say there will be toggles for adjusting camera settings. These are quick settings, and down there are better organized settings

> and I prefer to tab the interface rather than scrolling. One, so organizing settings in tabs. Above these tabs there is a quick settings toggles, and that's our new user interface for settings

24. The status line moves up into the outputs line's place when that is hidden (no gap). — **Status:** v111
25. No "SETTINGS" title. At the top: QUICK SETTINGS, one line of toggles (torch, frame rate, bit depth,
    resolution), and the three marks with their icons and 25 % size drop-downs (items 22, 23). — **Status:** v111
26. Below: TABS instead of scrolling, sections named by what they are about: DISPLAY (everything shown on the
    screen: peaking, zebra, the outputs line…), CAMERA (everything about the camera), and the rest by subject.
    — **Status:** v111
- **v111 (2.10.2026):** settings rebuilt: no title; QUICK row (torch that lights at once through the camera manager, FPS / BIT / RESOLUTION toggles, MIN, version); the three marks' icons with 25 % size drop-downs in one line; TABS CAMERA · OUTPUTS · DISPLAY · MORE. Camera screen: the status line spans the width and moves up when the outputs line is hidden (items 21–26). — **Status:** proven on his Pixel 05:10: the torch lights from settings at once (Android: "Torch for camera id 0 turned on") and stays on in the camera; tabs, toggles and drop-downs shown; status line at the top with no gap. Why the torch "did not work": the camera is closed while settings shows, so v104 lit it only after returning

## 2.10.2026, 05:12 — bit rates under CAMERA, named by what they are for; margins

> bit rate should be part of the camera settings. Don't call it bitrate at all, because I can see it says megabits per second. That's the bitrate. Just say recording and streaming, and by the number I understand what it does

> also, if you look at settings a graphical design, we can see that there is no margin between left and right side. So I'm looking at output and file touching the screen. The toggle switch is just glued to the right margin. Please adjust margins so they look decent, a few pixels

27. The two bit rates move to the CAMERA tab, labelled RECORDING and STREAMING (the number says Mbit/s). — **Status:** v112
28. Settings margins: nothing touches the screen's edges; switches not glued to the right. — **Status:** v112
- **v112 (2.10.2026):** RECORDING and STREAMING (the two bit rates) open the CAMERA tab; settings margins 28 dp inside the bars' insets (items 27, 28). — **Status:** proven on his Pixel 05:21 (CAMERA opens with RECORDING 50 Mbit/s and STREAMING 16 Mbit/s; OUTPUTS and FILE inset, switches off the edge)

## 2.10.2026, 05:25 — the sister app: an NDI monitor that remote-controls this camera

> After building this, please make a sister app. A sister app is just one NDI monitor which can connect to this camera when NDI is enabled, and then user choose this NDI stream. So user can choose NDI stream very simple, double tapping in the middle of the screen. It reads all the streams from the network And user can choose this camera, and then this camera is just giving all the same interface to remote user, but nothing is recorded. Everything is remote controlling. So let's build NDI monitor for this camera and any, and any other NDI stream in the network. This camera is just one of NDI streams, but since we have its code, we can automatically go to remote controlling mode.

29. A SISTER APP: an NDI monitor for any NDI source on the network; a double tap in the middle lists the sources
    and one is chosen. When the source is this camera, the monitor shows the camera's whole interface and drives
    it remotely (nothing is recorded on the monitor). REBUILD.md's Phase 4 (remote control over NDI metadata) and
    Phase 5 (monitor). — **Status:** open; platform to confirm with Marko
    — Platform (asked 05:27): **Android phone** (e.g. the Nothing Phone 2a watching and driving the Pixel).

> (05:28) inside latest release of this app, there should be both apps listed— sister app and main app

30. One release carries BOTH APKs: the camera and the sister monitor. — **Status:** open (with item 29)

> (05:40) now you have both of my phones on screen through screen copy, and you can test connections between NDI remote monitoring. There is full control. Both have developer mode, and you are free to screenshot and develop this sister app. So two ADBs, two screen copies right here on the screen. Please check it out also, you can stress test my camera on noting phone a to see where it freezes

31. Both phones on adb + scrcpy: test the sister app between them, and stress-test the camera on the Nothing
    Phone 2a to find where it freezes (and prove the freeze watch). — **Status:** started
- **v113 (2.10.2026):** MANTRA MONITOR, the sister app, a second module (monitor/) in the same repo and release: lists every NDI source after a double tap in the middle, watches one with the hardware decoder, remembers it; asks a Mantra camera for its state (remote control: v114/v115). CameraCommand moved to shared/kotlin for both apps. The release carries both APKs (item 29, 30). — **Status:** built, to test between his two phones
- **v113 PROVEN 05:52 between his two phones:** the Nothing Phone 2a's monitor found "LOCALHOST (Pixel 7 Camera)"
  after a double tap in the middle, connected, and received 1920x1080 HX at ~25 fps (~40 Mbit/s over Wi-Fi) through
  the hardware decoder. Faults seen: the picture is SIDEWAYS (the sensor's landscape frame from an upright phone)
  and STRETCHED to the portrait screen. adb taps cannot make a double tap (too slow); a real double click on the
  scrcpy window does. — **Status:** v114 fixes the shape and the turn
- **v114 (2.10.2026):** the monitor keeps the picture's own shape (letterboxed, never stretched) and has a TURN key: a quarter turn per tap, remembered per source. — **Status:** proven 07:12 between his phones (16:9 letterboxed, one TURN = upright, as the Pixel sees it)
- **MEASURED 07:14 on the Nothing Phone 2a (stress.py):** LOG → S-Log3 accepted → no frame for 1.2 s (the v104
  freeze watch fired and put the last request back) → "Camera error 4" (the driver closed the camera) → every later
  request "CameraDevice was already closed": the freeze. **v115:** a fatal camera error within 10 s of a risky step
  (a curve) takes the step back, marks that curve refused for this lens FOR GOOD (kept across starts), says "<curve>
  is not available on this phone — back as it was", and opens the camera again; any other fatal error also reopens
  it (at most 3 a minute). — **Status:** built
- **v116 (2.10.2026):** measured v115 on the Nothing: the crash was caught and blamed ("S-Log3 is not available on this phone — back as it was"), but the reopen came too soon ("Could not read lens 0"; by hand a few seconds later it opened). The reopen now retries at 1.5, 3, 4.5, 6 s. — **Status:** built

> (07:35) I don't know, did you understand me? We don't need the same app on both phones. On the A142 phone there should be a sister app only for monitoring. So we basically are extracting the monitor code. In the past there was an idea that one app will do the same everything, but right now what we need to do is have a separate app. I think you understood it, but I don't know what you are doing. Update me

- Answer: yes — Mantra Monitor is its OWN app (monitor/, applicationId com.mantraproductions.ndi.monitor, its own
  APK in each release), only for monitoring. The camera was put on the A142 only for item 31 (his stress test).
- **Stress test on the A142, v116 (07:31):** after the curve fix, every other risky key ran without a freeze:
  M ×3, focus ×3, PEAK/FALSE/ZEBRA ×2, lenses L1 and L2, FULL. Curves: S-Log3 and V-Log crash its camera driver
  (now caught, refused for good, camera back in ~2 s). — **Status:** item 31 done

> (07:40) remote control is a priority. Not breaking down this Nothing Phone app. It works without the log. That's fine. Please close this app on my Nothing Phone 2a, and please bring monitor up

32. REMOTE CONTROL is the priority: the monitor drives the camera. The camera stays on the Nothing (works without
    log); close it there and bring the monitor up. — **Status:** started
- **v117 (2.10.2026): REMOTE CONTROL.** The camera reads commands from the monitor 10×/s (NDI metadata, polled
  without blocking under the sender's lock) and does exactly what its own key or tap does; twice a second it sends
  its state: keys (name, label, whisper, state, tint), status line, marks in stream coordinates, the armed mark,
  the turn. The monitor wears that interface: the camera's own RailButton (now shared) top and bottom, the marks
  over the picture, the status line; a key tap presses it on the camera, a picture tap goes to the camera's armed
  mark (focus / exposure / white balance there), the turn follows the camera. Nothing is recorded on the monitor.
  RemoteTest: the vocabulary round-trips. — **Status:** PROVEN 07:47 between his phones: the monitor showed the camera's keys, status line and marks, upright by itself; PEAK pressed on the Nothing switched peaking on the Pixel (and back); a tap on the keyboard in the monitor moved the Pixel's white balance triangle there and locked neutral in 2 steps, both screens green

## 2.10.2026, 08:00 — keep testing; stream bit rate from the monitor; the monitor's own data rate and frame rate

> yes continue testing and optimising , also add buttons to sister to toggle between streaminf data rates so basicaly adjusing ndi hx bit rate to compromise between quality and frame rates also on monitor side add status of monitor dta radte frame rate

33. On the monitor: keys to step the camera's NDI HX bit rate (quality against smoothness). — **Status:** v118
34. On the monitor: its own status — the data rate it receives (Mbit/s) and the frame rate it shows (fps). — **Status:** v118
35. Keep testing and optimising the remote. — **Status:** ongoing
- **v118 (2.10.2026):** HX − / + on the monitor step the camera's stream bit rate live (4 8 12 16 24 32 50 Mbit/s; the encoder takes it without a restart; the camera reports the rate back); the monitor's own line: MONITOR fps shown · Mbit/s received, once a second (items 33, 34). — **Status:** built
- **v119:** the monitor's numbers from running totals (v118 alternated 0.0 and 14 fps / 28 Mbit/s: a counter zeroed by its reader). — **Status:** built
- **v120:** MEASURED the bursts: the Nothing's Wi-Fi in power save (throughput 0 then 46–82 Mbit/s catching up) and the real stream at 50 Mbit/s (direct path: the take's encoder), while the state said 16. Both apps now hold the low-latency Wi-Fi lock while NDI flows; the camera reports the bit rate actually encoded. — **Status:** built
- **v121:** the monitor logs each second what arrives, what it shows and what the decoder had no room for; the decoder asks for low latency and real-time priority. HX − measured: 50 → 32 → 24 → 16 Mbit/s live on the Pixel. — **Status:** built
- **v122:** MEASURED v121 broke video on the Nothing (its decoder refused low latency, the loop returned, the camera stopped sending: Pixel 0 Mbit/s; with v120's monitor back, 44 Mbit/s). Now low latency is tried, then the plain setup, and a failed decoder never ends the connection. — **Status:** built
- **v122 measured:** the low-latency decoder WORKS on the Nothing; the real fault: video flowed 10–19 fps for ~5 s, then the camera stopped sending (since v117's metadata). **v123:** the metadata gets its own native lock; create/destroy take both. — **Status:** built
- **MEASURED after v123 (08:30–08:45):** at 50 Mbit/s steady ~25 fps for 20 s, but over 40 s dropouts of 2–4 s:
  the PIXEL stops sending (0 Mbit/s) at those moments. Both phones on 2.4 GHz channel 2, the Pixel at −68 dBm with an
  86 Mbit/s link: a 50 Mbit/s stream through the router needs ~100 Mbit/s of air. Separately, a live drop to 16
  Mbit/s made the Pixel's HEVC encoder go silent (no frames, no error) — twice. **v124:** a keyframe is requested
  after every rate change; the camera's ENCODER WATCHDOG restarts the pipeline when no frame leaves the encoder for
  two seconds while streaming (max 3 a minute), and a restart keeps the monitor's chosen rate. — **Status:** MEASURED 08:45 at 16 Mbit/s: the encoder kept going (no restart needed), 25 fps at 17–18 Mbit/s for most of 40 s, one 7 s gap where the Pixel stopped sending and resumed by itself (2.4 GHz air, not the app)

> (08:50) We are closing the session. Write all necessary documents and go to sleep

- Session closed 2.10.2026 08:50. Open for next time: remote pinch; the camera's settings over the wire; put FILE back
  on his Pixel (disarmed since the NDI tests); the 16 KB-alignment notice of the NDI libraries.

## 2.10.2026 — the ramp: every recalibration glides like a hand on analog gear

> please write this as a rule and fix this app the same as focus exposure circle or wide balance circle. When the user clicks to recalibrate the camera, the change itself is not happening suddenly. It emulates the analog equipment, so it happens like the person doing it manually understands. There is a ramp, understand. So if I'm in exposure and I expose to a new area in my image, then the camera just does a gradual change in that way. I can record that change. Understand. Change must be pleasant for recording

36. The rule, in MANTRA_MANIFEST: a recalibration (focus, exposure, white balance) never jumps; it ramps like a hand on analog equipment, pleasant to record. — **Status:** started
37. The camera: focus SQUARE, exposure CIRCLE, white balance TRIANGLE all ramp to their new value. — **Status:** started

> also, you need to encounter overlapping of circle, square, and triangle. If they're overlapping and the user clicks basically on all three at the same time, all three are doing their thing. So it's possible to click multiple of them if they are in proximity or overlapping

38. One tap where the square, circle and triangle overlap (or touch) sets every mark under the finger: focus, exposure and white balance together. — **Status:** started

> in any ramping algorithm you need to implement ease-in and ease-out curves. Please

39. Every ramp eases in and eases out: it leaves slowly, travels, and arrives slowly — never a linear start or stop. — **Status:** started

> now there is a white paper on my keyboard. Please use it to test the slider for manual white balancing. Does the color shift as it's supposed to shift, or is it unnatural? Please check the slider, do screenshots, and give me a report

40. Test the manual white balance fader on the white paper: does the colour shift as it should, or unnaturally? Screenshots and a report. — **Status:** started
- **v125 MEASURED (2.10.2026 10:02–10:03, Pixel 7, screen recordings):** the exposure circle walks smoothly (picture Y 81 → 61 over 2 s, no frame step over ~1/255), but in two ramps with a pause: the phone's tone curve answered 0.58 of the first move. The triangle's ramps are smooth but the first step overshot (red answered at 4.2): the paper went cool and came back. **v126:** exposure learns the answer; white balance starts cautious and keeps what it learnt; the glide path in the trace. — **Status:** built, v126 on the Pixel, not yet measured
- **Item 40 MEASURED (10:08–10:09, v126, lens 3):** the WB fader is WRONG. From 6500K down to 3200K the paper turns yellow-olive instead of blue (b/g 0.97 → 0.80), green creeps in (+12), and below 3600K it turns back (b/g 0.80 → 0.87). Cause: the Pixel's published colour matrices give a nearly flat, non-monotonic model (B/G gain 1.00 at 6500K, 0.84 at 3600K, 0.91 at 3200K, where a real sensor needs about 1.6–2× more blue at tungsten); the fader carries the anchor along that wrong curve. Also the range is only 3200–6500K. — **Status:** reported, fix proposed

> yes white balance is priority

- Go-ahead on the fader fix (item 40), white balance first.

> please do the ramping when I change my shutter speed in degrees from button to button

41. The shutter angle keys (0° 90° 180° 270° 360°): moving from one to another ramps, eased in and out, like the marks. — **Status:** after the white balance fader
- **v127 MEASURED (10:19–10:20, Pixel 7 lens 3, white paper):** the lens's presets give a real curve (2700K R 1.33 B 2.88; 5500K R 2.15 B 1.77; 6500K R 2.22 B 1.71; 7500K R 2.58 B 1.47); the room reads 4700K (v126 said 6500K). The fader now goes the RIGHT way all along (gains monotonic 10000K → 2000K) but too far in hue: at 2800K the paper's red falls to ~0 (cyan, not blue), at 10000K red clips (orange), because the gains moved under the room's matrix. **v128:** each preset's matrix is measured too and moves with the gains. — **Status:** built
- **v128 MEASURED on the RECORDED files (10:28–10:29, HLG decoded to linear):** room 4600K paper r/g 1.02 b/g 1.04 (neutral); 2800K paper r/g 0.41 b/g 1.75 (blue, as predicted 0.51/1.78); 7800K paper r/g 1.59 b/g 0.55 (amber). The red jacket and the cream drum keep their colour. The Pixel reports one matrix for all presets, so the gains alone are its own presets' move. The cyan seen earlier was the HDR screen, not the file. FILE is armed again on the Pixel. — **Status:** item 40 DONE (fader natural, 2000–10000K)
- **v128 MEASURED, item 41 (10:31):** the shutter keys ramp 1/50 → 1/100 in 1.9 s, eased in and out. But with ISO on A the app's loop stepped ISO four times a second (247 → 259 → 280 → 322 → 374 → 420 → 454 → 476), lagging the shutter: the picture would dip, then climb back in steps. **v129:** during a key's ramp the auto half moves against it every frame (exposure held); the loop's corrections walk on a critically damped spring (eased, never past). — **Status:** built

## 3.10.2026, 05:50 — the remote must be the same camera; settings close; NDI on its own; the lens drawer

> The Remote Viewer is not good. The user interface is not the same as the original one. The experience for users should be seamless, so it's exactly the same user interface, but also the settings. The settings should be the settings on this local device, on the remote phone, not the— not opening the settings on the on the original phone because when settings are open there you cannot reach them, you cannot change them. That's not happening. But anyway, we need settings for this one. And in settings I need also to have a clean interface. Actually, we don't need settings if you think about it. We need to repurpose the buttons, no? So full screen button to make it full screen on the phone, which I'm not there. Doesn't make any sense. No, it means this, this phone, remote phone, should have clear interface. And by clear interface, I mean the only button available on the— so it's specially interfaced— available on remote phone is a record button and record counter. That's the full screen. When it's not the full screen, it must— all interface must make sense, so nothing is happening only on remote, like settings for example. So let's think about how to design to have seamless experience between 2 phones and also detect when there is zero frame transmission to somehow restart connection or something.But I'm thinking even further to simplify this. If there is a protocol which can enable remote control like VNC, and to distribute this app with NDI means paying the fees, and I'm not in that position, I think remote control should be simplified simply having a remote session view like NVC. And then there is no problem. I'm actually pressing everything on remote, and that's, that's the thing. Is that possible to arrange? I'm asking you.Also, whatever protocol is used by SCRCPY screen copy, the result is fantastic. Image is so good and it can work over Wi-Fi. So I am not sure, can we do some kind of Wi-Fi debugging and then actually have screen of the second phone? So I am thinking about these 2 technologies, remote control VNC and something used inside Screen Copy, which is open source, so we can see what is it using.also, for the settings, i need close button to exit settings , also, NDI Stream should start actually as soon as it's enabled. It's not any more connected with the record button. Also lens is drawer. We can call it drawer because when I press lens, it should actually take all available space so it's easy to click on each number or each lens. So just when the lens is open, all other icons are gone. The whole area is dedicated to lenses switching. And since now there will be much more space, you can make millimeters differently aligned. Maybe next to the letter instead of under it. And maybe you can also add aperture opening for each lens and resolution because we have space.

(Said with it: both phones on adb and scrcpy; screenshots from the phones themselves (adb screencap), never the Mac screen; ask by voice and keep developing.)

42. The monitor wears exactly the camera's interface, and every key on it makes sense there: nothing opens only on the camera phone (settings opened on the camera cannot be reached from the monitor). — **Status:** started
43. The monitor's FULL SCREEN is its own clean view: the picture, the record key and the record counter, nothing else. — **Status:** started
44. Detect zero frames arriving and restart the connection by itself. — **Status:** started
45. Question: can the remote be a remote session like VNC (pressing everything on the camera), or the scrcpy way over Wi-Fi, instead of NDI with its fees? Answer with the design. — **Status:** started
46. Settings: a close key to leave them. — **Status:** started
47. The NDI stream starts as soon as NDI is enabled, no longer tied to the record key. — **Status:** started
48. The lens key opens a DRAWER: it takes the whole area, every other key gone; millimetres next to the letter, and each lens's aperture and resolution. — **Status:** started

> (05:58, answer to the voice proposal) Yes, build everything proposed. You have a good uh uh Reasoning

- The proposal he said yes to (item 45): **our own link, the scrcpy way, no NDI fees.** The camera sends its own
  window (picture, keys, settings) as H.265 straight to the monitor over Wi-Fi, and every touch on the monitor is
  pressed on the camera (a remote session of the camera app, no adb, no developer mode). When the monitor goes full
  screen, the camera sends the clean picture only and the monitor shows just the record key and its counter. NDI
  stays as an optional output for vMix / OBS. — **Status:** go-ahead, after items 46–48

## 3.10.2026, 08:00 — alone with the Pixel (Marko away, no voice); Mantra Link built

> exactly at 8 a.m. in one hour, I'm going to take out my phone not in front of a and I'm going to leave connected Pixel 7 through debugging. So please use this opportunity to test my camera, whatever needs to be done there. Unfortunately no remote, but if you can somehow simulate input and use one phone for both functions, or maybe use a virtual phone emulator and real phone in combination as two phones and make this remote control working through screen copy protocols
> after 8 you are going to be left alone to do everything automatically. That means no audio subtitles output. No need. After 8 until 8 we can talk

- **v131, MANTRA LINK** (items 42–45): Link.kt (the wire), LinkServer.kt (camera: its window by PixelCopy → H.264
  1280 px 24 fps 8 Mbit/s over TCP 48100, announced by NSD; touches played into the window as MotionEvents; keys by
  name; BACK), LinkClient.kt (monitor: decode to the screen; every touch back; ZERO FRAMES: 2.5 s of nothing →
  reconnect). FULL pressed by the monitor's finger → the MONITOR goes clean (the preview only, with ● REC and the
  counter; a double tap returns). Three fingers open the sources on the monitor; its back key goes to the camera.
  Test: the Pixel as camera, the Pixel 7 emulator as monitor (`am start --es link 192.168.1.102`). — **Status:** PROVEN
  08:23–08:40 (Pixel camera, emulator monitor upright): 24–25 fps at ~8.5 Mbit/s, the camera's window 576x1280;
  a tap on the monitor's L opened the lens drawer on the Pixel, a lens key changed the lens; FULL from the monitor
  made the MONITOR clean (the picture, ● REC) while the Pixel kept its keys with FULL green; REC from the clean
  monitor reached the camera (it answered "FILE is not armed"); the gear opened the camera's settings ON THE
  MONITOR, FILE was armed there and ✕ closed them (the encoder followed: 1280x576 for the landscape settings, back
  to 576x1280); a 4.9 s take recorded from the monitor's window (117 frames, 0 refused) with the counter running on
  the monitor; the camera app killed: monitor 0 fps, then back at 24 fps 1 s after the camera returned, untouched.
- **v132:** the camera's last words in its state; a clean monitor shows them 3 s above REC (a press that does
  nothing says why). — **Status:** built
- **v132 MEASURED (08:54):** the clean monitor showed the camera's words ("Recording → DCIM/…mp4") above the key,
  the key turned ■, the counter ran. FOUND: the bar grew when the counter appeared and pushed the key to the left;
  the press to stop hit the counter (the take ran 3:45, 5601 frames, 0 refused; my two test takes were deleted).
  **v133:** the key never moves (an empty slot on its left balances the counter's on its right). — **Status:** built
- **09:00–09:15 (alone):** PINCH over the link PROVEN (a two-finger pinch sent over the wire: "mark size 1 pinched";
  reversed after, marks restored). Mac-side lesson: macOS keeps the terminal's Python off the LAN (0 bytes, keys
  ignored); `adb forward tcp:48101 tcp:48100` works. **LEFT CHANGED ON THE PIXEL:** my LOG key tests moved the curve
  off HLG (now S-Log3, the cycle did not land on HLG before the usage limit): set it back to HLG with the LOG key.
  v133 (record key fixed in place) is built by CI, not yet installed or tested.

## 4.10.2026 — stress test with both phones on scrcpy

> two phones are connected over screen copy. Please stress test the camera and test all its new features

49. Stress test the camera with both phones (Pixel 7 camera, Nothing Phone 2a monitor) on adb and scrcpy, and test every
    new feature since v124: the ramps (marks, shutter keys, eased), overlapping marks, the WB fader, settings ✕, NDI on
    enable, the lens drawer, Mantra Link (window, touches, pinch, clean monitor, REC and counter, zero-frame reconnect),
    v132's words, v133's fixed record key. — **Status:** v133 installed on both (camera on the Pixel, monitor on the
    Nothing), link up; paused at 06:00 for items 50–51 ("just the UI")

> Just the UI: fix when the lens choice is open between lenses, please add a vertical line so there is division so it doesn't look like one stream of text.
> Make sure the noting phone is always following the orientation of the pixel phone in this app. Now a phone is vertical and view is horizontal, and we never want that because we have just tiny tiny view. Please fix that.

50. The lens drawer: a vertical line between lenses, so the row is not one stream of text. — **Status:** v134, PROVEN 06:05 on both screens (Pixel and its window on the Nothing)
51. The monitor (Nothing) always follows the camera's (Pixel's) orientation in the link: a portrait camera means a
    portrait monitor, never a tiny portrait window inside a landscape screen. — **Status:** v134: the monitor takes the
    orientation of the window it receives (taller → portrait, wider → landscape), its own sensor no longer decides. PROVEN
    06:05 upright: the Nothing stands and the Pixel's window fills it (24–25 fps, ~8 Mbit/s; L and L1 pressed through the
    link). The Pixel turned sideways is for his hand (the camera holds its own orientation, so adb cannot fake it).

## 9.10.2026, 11:20 — v135 field test on the Pixel 7 over wireless adb (session "NDI Camera Local")

> so the testing we call the holy land is unplugged. The cable is plugged and the phone is in tethering mode, so now we can test the streaming of NDI over the cable, limiting it to the cable only. So this app needs to limit, because the NDI on the computer does everything automatically. If I want to force the cable, I want to have a switch so same as other streaming destinations, we will have switch NDI stream Wi-Fi, NDI stream cable, and enable phone tethering

52. NDI over the cable ONLY when the cable is chosen: the Mac's NDI finds the phone on every network by itself, so the
    app must hold the stream to the cable. In settings, like the other outputs, switches: NDI STREAM WI-FI, NDI STREAM
    CABLE, and ENABLE PHONE TETHERING. — **Status:** asked; for the cloud session (branch claude/friendly-goodall-61mhpx).
    MEASURED 11:18 on v135 with USB CABLE chosen: the sender says "ncm0 10.67.163.234 (USB CABLE)", but the NDI ports
    5960/5961 listen on 0.0.0.0 (reachable on 192.168.1.102 over Wi-Fi too) and Android.local is announced with both
    10.67.163.234 (Mac en19, the cable) and 192.168.1.102 (en0/en17), so a receiver may take the Wi-Fi path.

## 22.10.2026 — the camera under adb control (session "shruti box development")

> "we are working on Shruti app. Please see wireless debugging. There is Pixel phone 7 ready to test camera is also there active and it is pointed to MIDI controller. You can also use my mantra camera. You have all the data about it because we programmed it and now make it also available to fully control through ADB. So you can, for example, if its picture is too dark, you can turn on the light on my camera. If picture is in wrong exposure, you can adjust exposure parameters in the camera. If there is wrong white balance, you can adjust that. So make ADB controller for my mantra camera which is already installed and check if it's latest version. And then for Shruti box, you will see on the camera there is a MIDI controller through camera you can see what it is. Find the right model and make this MIDI controller control my Shruti box. Let's do that first" (Marko, 22.10.2026)

53. An adb controller for Mantra Camera: from the Mac, the light (torch) on and off, exposure, white balance and the other picture settings, so a session can fix a dark or badly balanced picture by itself. — **Status:** asked; v138 on the Pixel 7 is the latest release.

> "Try also changing the lens on the camera, because the second lens gives much better picture and it can zoom in a little bit. That's better. Lens two is the best." (Marko, 22.10.2026)

54. The adb control chooses the lens: `mcam lens 2`; lens 2 is Marko's choice for the picture. — **Status:** v141 asked; v139 has torch, ISO, shutter, white balance proven on the Pixel 7 over wireless adb (07:36–07:38), EV kept from v140.

## 24.10.2026 — the name: Mantra Manual Camera

> "Mantra NDI's camera repository. First, we need to rename this camera, not in this confusing name. Name the project and repository Mantra Manual Camera." (Marko, 24.10.2026)

> "there is no problem in making new app, please rename it it can be as new app, old one will be uninstalled" (Marko, 24.10.2026)

55. The project and the repository are Mantra Manual Camera (`markoboskoauroville/mantra-manual-camera`), and the app id is `com.mantraproductions.manualcamera`: a new app beside the old `com.mantraproductions.ndi`, which is uninstalled by hand; its settings do not carry over. The adb action is `com.mantraproductions.manualcamera.ADB`, `scripts/mcam` follows. The Kotlin package and Mantra Monitor keep their names. — **Status:** v142 asked; the repo renamed by Marko.

## 24.10.2026 — one circle: ISO or WB, and the shutter by hand

> "Please update the app with new feature. We used to have square, triangle, and circle. Now it will be different for assistance on adjusting the manual camera. Focus is the same as before, and we have circle for exposure, triangle going out. No, actually triangle will be also a circle, so we have square and 2 circles. And actually, we're going to have a square and one circle because this is the trick. So circle, a long press on the circle, change its mode. Normal mode is— I mean, normal mode, I am selecting the mode. So I am selecting the mode. One mode is to tap to adjust exposure with ISO only. Then second mode, so long press the mode pop-up toast is popping up to change the mode. It's a white balance. And the third option, which is not the mode, which is just to set in the shutter. So idea in manual camera is that what is fixed in the video mode is always shutter speed and aperture. Aperture is fixed, so nothing to change. So shutter speed is fixed. It is never automatic. Of course, in the manual sliders I can put it automatic, but with this round thing, what I expect to change manually with plus and minus buttons— shutter speed. Or rectangle can be ISO exposure or white balance. So triangle going out, that's all improvement in user interface. And then So people know where they are. Under the, the circle, you need to write in small letters ISO or WB, white balance, and that's all." (Marko, 24.10.2026)

56. The square (focus, as before) and one circle; the triangle is gone. A long press on the circle opens its menu: **ISO** (a tap walks ISO alone to 18% grey on the circle, the shutter held where it is), **WB** (a tap balances white on the circle, as the triangle did), and **SHUTTER − / +**, a video stop at a time (1/24 … 1/50, 1/60 … 1/8000, never longer than a frame), by hand from then on. The mode is written small under the circle: ISO or WB. adb: `mcam circle iso|wb|tap|+|-`. — **Status:** v143 asked; built, not yet tried on a phone.

## 26.10.2026 — the ADB switch, every setting over ADB, and UPDATE in the settings (session in ~/Developer/claude-code)

> "please confirm that this camera has in software in code ADB door, which can remotely adjust any parameters of this camera, any settings, to take complete control of this application. If it's not existing, it needs to be done in a setting, you need to add a switch ADB control, which is on by default" (Marko, 26.10.2026)

> "yes, please build that control now Update the app, and one more thing: inside the settings, you need to add an update button to update the app. Clicking on "Version" is a bit difficult. Settings, then there is F 25%, E 50%. You need to compress this and add an update button in that same row." (Marko, 26.10.2026)

57. ADB CONTROL, a switch in the settings, on by default: off, the camera answers adb with "ADB control is off" and does nothing. With it on, adb reaches every setting of the settings page, not only the picture (lens, light, ISO, shutter, EV, WB, focus, circle, keys from v139–v143). — **Status:** v144 built: MORE → ADB CONTROL; `mcam settings` lists every setting, `mcam set NAME VALUE` sets one through the screen's own limits (what the camera is built with makes it again, the stream kept), `mcam restart`, `mcam update`. adb cannot open or shut the door itself. PROVEN 26.10.2026 04:54 on the Pixel 7 (cable): settings listed (39), set fps 25/30 and zebraLevel taken, bad values refused, switch off → "ADB control is off", on → answers again.

58. An UPDATE button in the settings, in the row of F 25% and E 50% (that row compressed to make room): it does what a tap on the version does. — **Status:** v144 built: UPDATE asks GitHub; newer → the camera APK handed to Android to download and install, else "vN is the newest". PROVEN 04:54: the key sits after F 25% and E 50%, the toast said "Update: v144 is the newest".

## 26.10.2026 — FOCUSING under the square, with its distance; the focus measured on every lens

> "In the same style as we have ISO under the circle, I want to have focus under the square, with two modes: focus auto and focus manual. While it's measuring the focus, there will be "focus auto." While it's locked, it will be "focus manual." This is to explain what's going on. Also, please check if the focus works. You need to control the focus. Go to all focal ranges and check if the actual camera application can control the focus of this camera in every lens which is actually enabled to be focused. Please see if that's fine enough. I want to have the maximum possible resolution of focusing movement, so I can go in millimeters. What are the steps which the camera or hardware accepts for focus? The slowest steps. Under the focus text, "focusing auto." We don't do focus manual. We do it like this: "focusing set column auto focusing." Then you need to put the meters, which are centimeters or meters, where the focus is actually adjusted, so we always know the distance." (Marko, 26.10.2026)

59. Under the square, small, as ISO / WB under the circle: FOCUSING: AUTO while the camera measures, FOCUSING: SET once it holds, and under it the distance the lens is really at, in centimetres or metres. — **Status:** v145 built (mm under 10 cm, cm with one decimal under 1 m, m beyond; FIXED FOCUS on a lens without travel); adb `mcam focusd 25cm|250mm|4.0` and the state's focus_dpt, focus_at, focus_closest_dpt, focus_calibration.

60. The focus measured from the Mac on every lens that focuses: whether the app moves it, over the whole range, and the finest step the hardware takes (he wants millimetres). — **Status:** MEASURED 26.10.2026 on v145 (`mcam focusd`, the capture result read back): L1 ultra wide 17 mm, L4 and L5 front: FIXED focus, no motor. L2 25 mm (fused) and L3 50 mm are the same main sensor and motor: infinity to 10.5 cm (9.52 dpt), calibration APPROXIMATE, every position asked is reached (asked 2.381 → reached 2.381). The motor's finest step is 0.034 dpt (about 280 positions infinity → 10.5 cm): about 0.4 mm at 10.5 cm, 0.8 mm at 15 cm, 2 mm at 25 cm, 8 mm at 50 cm, 3.5 cm at 1 m, 14 cm at 2 m. Millimetres hold only closer than about 30 cm.

## 26.10.2026 — no gear: the free space opens the settings; the last take inside the record button

> "another feature is to remove this settings icon, and clicking on the free space will open the settings. That's why you need to color this free space thing in yellow, and then you can spread other controls there. There will be more space, so there's actually less. Inside the record button, instead of this white thing, you need to display the thumbnail of the last clip, either it's photography or video, whatever is last captured with this application. The thumbnail will be inside the record button, and the record button is still the record button" (Marko, 26.10.2026)

61. The gear key goes. The free-space key (44.6 GB / 4:20 h) opens the settings and is coloured yellow; the other keys of the bottom rail spread into the room the gear left. — **Status:** v145 built: the gear is gone, the free-space key is yellow (#F5D90A, red under ten minutes) and opens the settings; the bottom rail's keys share the room.

62. Inside the record button, in place of the white disc, the thumbnail of the last thing this app captured, photo or video, whichever is newest; the button stays the record button. — **Status:** v145 built: a still's picture or a take's first frame, kept as files/last_capture.jpg across runs; red while recording as before.

> "also check if it's possible to adjust the brightness of the LED in the Pixel phone, so in our settings we can have brightness control for the LED" (Marko, 26.10.2026)

63. The light's brightness: find whether the Pixel 7's LED takes a strength level; if it does, a brightness control in the settings. — **Status:** MEASURED 26.10.2026: the Pixel 7 (Android 17, API 37) publishes torchStrengthMaxLevel 45 in a camera session (default 45) and 45 through the camera manager (default 21). v145 built: CAMERA → LIGHT, brightness 1–100 %, at once on the lamp; adb `mcam torch 30%`, `mcam set torchLevel 30`.

## 26.10.2026 — the remote over ADB: wireless debugging and scrcpy's picture

> "now, since we have wireless debugging, we can also upgrade my remote control app to use this protocol: wireless debugging to control the app. I'm not sure what is in that remote controller now, but if it's something, whatever is there, we can have a switch between that thing and ADB. You can name it somehow, but the idea is that ADB can probably now... I want to just experiment. I guess ADB can be more precise and faster than what we have there. Remote controller using ADB, and through ADB you transfer the picture using open-source code from screen copy. Our remote controller is using that" (Marko, 26.10.2026)

> "in settings, there must be a guide on how to set up ADB wireless. Then these two phones are talking through Wi-Fi, and they are able to control each other" (Marko, 26.10.2026)

64. Mantra Monitor (the remote) gets a second way to the camera beside today's (MANTRA LINK over NDI): ADB over wireless debugging, the keys as adb commands and the picture through scrcpy's open-source server, with a switch between the two, to compare which is faster and more precise. — **Status:** v150 PROVEN 26.10.2026, the emulator (Mantra Monitor) driving the real Pixel 7 (the camera) over Wi-Fi: paired by code (libadb-android 3.1.1 + Conscrypt 2.5.3; the system's own Conscrypt hides exportKeyingMaterial), connected over TLS to 192.168.1.101:43917, scrcpy 4.1's server (an asset, from the Mac's Homebrew scrcpy) pushed through ADB sync and started; the camera's whole screen at 30–33 fps, 0.7–2.3 Mbit/s, H.264 1600 px; one tap on the meter's 30 cm circle in the remote racked the Pixel's lens 20.8 → 29.9 cm (scrcpy's 32-byte touch event, unchanged in 4.1). scrcpy 4.1's wire, MEASURED from the Mac: dummy byte; device name and codec only after the control socket is open; codec id is 4 bytes; a SESSION packet (top bit, then width, height) first and on every size change; frames with bit 62 config, bit 61 key frame. Remembers the last camera's address; reconnects at start.

65. In the settings, a guide to set up wireless debugging, so the two phones talk over Wi-Fi and each can control the other. — **Status:** v150: the same guide (shared/kotlin AdbGuide.kt) in Mantra Monitor's sources (GUIDE, seen on the emulator) and in the camera's settings, MORE → WIRELESS DEBUGGING: THE GUIDE; the steps for the other way round are the same with the phones swapped.

> "we have a remote controller in two modes: ADB or not ADB. Maybe that can be the difference in the app. There will be some kind of ADB status line that says ADB is enabled, and then it works through ADB. If I disable it, it works through any protocol you decide to use without ADB" (Marko, 26.10.2026)

66. The remote (Mantra Monitor) has two modes, ADB on and ADB off, and a status line that says which: "ADB enabled" and it drives the camera through ADB; off, it works through the way without ADB (today's MANTRA LINK). — **Status:** v150: in the sources, ADB ON / OFF (on by default) with its line ("ADB ENABLED: a camera is watched and driven through wireless debugging" / "ADB OFF: cameras are watched over MANTRA LINK and NDI"), PAIR, GUIDE, and the phones wireless debugging announces; watching, the status line reads "ADB ENABLED · Pixel 7 · 192.168.1.101:43917 · 33 fps · 0.7 Mbit/s". With ADB on, a LINK camera that also announces ADB is reached over ADB.

## 26.10.2026 — built on the Mac, both apps in one release, the manifest every time

> "put in the manifest every time something new is updated. We update two apps: one is the main app camera and the remote app. On the same latest page, the latest version should be both updates, both latest versions I hope you are building this app on this computer and then sending it to GitHub under Releases. We are not using GitHub Actions to build the APK. It's built locally." (Marko, 26.10.2026)

67. (v145 on GitHub is the Actions build of 3eeb25c, released before the cancel landed; the Mac's build with FOC: and the rack is v146.) Every version is built on the Mac, the camera and Mantra Monitor together, and both APKs go up in the one release marked Latest; GitHub Actions no longer builds on a push (by hand only, workflow_dispatch). Every update is written into the manifest the same turn. — **Status:** from v145.

## 26.10.2026 — FOC: and the number; rack focus; the focus puller's meter on the remote

> "I want the text focusing. It can be shorter. It can be FOC. Then column, then centimeters. The set thing. I don't need to have it. Understand: while it's searching, instead of auto, it goes to display the number. Also, we want to rack focus between different focusing points. Please test rack focusing. It goes easy in, easy out, as the focus puller will do it on the set. We also need to develop a remote control focus-pulling user interface, where the focus puller can mark on the meter, which will be on the screen, different distances, any number, and then with the play button go from one to the other.
>
> Actually, this will be like this. Any point he marks is key points or key frames. There will be small circles, like our ISO circle here, which will be the size of the index finger. He can tap on any of these points, and the focus will just slide to that point.
>
> There will be one square, one triangle pointing down, in a different color than the key frames. Key frames on the meter will look like an empty triangle looking down, and the current focus will be a full white triangle. When the user clicks on these circles, the triangle will, in this matter of animation, ramp up with the speed and then ramp down and fall on that focusing point where the user presses." (Marko, 26.10.2026)

68. Under the square only "FOC: 21.7 cm": no AUTO, no SET; while it searches the number runs. — **Status:** v145 PROVEN on the Pixel 7: "FOC: 15.0 cm", the number running while it searches or racks.

69. Rack focus between points, eased in and out like a focus puller on set: tested from the Mac. — **Status:** v145 PROVEN 26.10.2026 with `mcam rack 15cm 3000` (smootherstep): 50.1 cm at 0 s, 49.2 at 0.36 s, 37.1 at 0.98 s, 22.9 at 1.63 s, 17.3 at 2.22 s, 15.1 at 2.89 s, 15.0 from 3.25 s — slow out, fast through the middle, slow in.

70. THE FOCUS PULLER'S METER on the remote: a distance scale on the screen; he marks any distances as key points (empty triangles pointing down, each with a circle the size of a fingertip under it, like the ISO circle); the lens is a full white triangle; a tap on a circle racks the focus there, the white triangle accelerating and easing to land on the point; a play button goes from one key point to the next. — **Status:** v149 ON THE CAMERA, PROVEN on the Pixel 7 26.10.2026 with real taps (adb input): Settings → FOCUS → FOCUS PULLER; the meter down the right edge upright (along the bottom sideways), a dioptre scale with 10, 15, 20, 30, 50 cm, 1 m, 2 m, ∞; key points as amber empty triangles with 20 dp circles; the lens a full white triangle from the capture result. A tap on the 15 cm circle racked 29.9 → 15.0 cm in 2 s (29.0, 24.8, 19.2, 16.1, 15.0), eased; ▶ went 30 → 50 → 15 cm, skipping a point the lens is on; a long press on 1 m removed it; a tap on the scale added 50.4 cm; dragging the white triangle pulled 15 → 30.2 cm. Key points kept across starts. adb: `mcam meter on|off|add 40cm|clear|keys`. The remote's own meter waits for 64–66.

> "To test the remote controller, you can open up our local Pixel 7 emulator and control the real Pixel 7 from the emulator to test all its functions. Focus pulling is the function of the second phone and on this phone as well. This is in Settings. We can put focus pulling UI, and it must use the longest edge of the phone's UI, not the short one." (Marko, 26.10.2026)

71. The remote is tested from the Pixel 7 emulator on the Mac driving the real Pixel 7. The focus puller's meter (70) is on both: the remote phone and the camera itself, switched on in the settings; the meter runs along the phone's longest edge. — **Status:** v150: tested from the emulator as asked. Over ADB the remote shows the camera's own screen, so the camera's meter (v149) is on the remote too and works from it (proven: the 30 cm circle tapped on the remote). The real remote phone (the Nothing, 192.168.1.102) is Marko's try.

## 26.10.2026 — a FOCUS tab in the settings; focus speed and tracking speed in mm/s

> "Please, in a setting, there should be a special tab for focus, because focus is now becoming very sophisticated with all its helpers. We have some helpers for manual focusing. How do we call this? Picking focus, picking. Yes.
>
> Then we need to have a speed of tracking and a speed of focusing. The speed should be in centimeters or millimeters per second, whatever is better. I think let's put millimeters per second. That's the speed of focusing. Our app is going to have that." (Marko, 26.10.2026)

72. The settings get a FOCUS tab with every focus helper in it: peaking ("picking"), tracking, hold and the focus speeds. — **Status:** v147 built and seen on the Pixel 7: FOCUS = SPEED, Focus (hold, ramp), Peaking.

73. Two speeds in millimetres per second: FOCUS SPEED (a tap, a rack, a pull to a point) and TRACKING SPEED (how fast the lens follows a tracked subject). A move's time is its distance over the speed, eased in and out. — **Status:** v147: 0 (leftmost) is CAMERA, the camera's own glide; 10–1000 mm/s. MEASURED at 100 mm/s, 60 cm → the E16 at 22 cm: the camera's search first shows the target for about 0.4 s, then the lens goes back to 60 cm and walks there in 3.6 s (57.1, 46.0, 33.7, 25.5, 22.5, 22.0 cm). That jump is the price of a timed walk: the target is only known by searching. Left at CAMERA until Marko chooses; `mcam rack 22cm` (no time) walks at FOCUS SPEED with no search.

> "Now we have the Focus tab in the settings, and we try to break the settings into more logical tabs. See how we can do this.
>
> We have not so much scrolling down. Mostly it's going left or right." (Marko, 26.10.2026)

74. The settings in more, smaller tabs, each short enough to need little scrolling; a swipe left or right goes to the next tab. — **Status:** v147, seen on the Pixel 7, all seven swiped through: CAMERA (light, picture path), FOCUS, TRACK, OUTPUTS (the two bit rates, then the switchboard), SOUND, LOOK (was DISPLAY), MORE. A swipe that starts on a slider or a drop-down moves the slider, not the tab.

> "At the end of the day, when I say "close the session," you need to go to Cloudflare and simply update the app with the latest screenshots, and the website should be updated." (Marko, 26.10.2026)

75. "Close the session" also updates the app's website on Cloudflare with the latest screenshots and the latest version. — **Status:** the rule is in MANTRA_MANIFEST modules/close-the-session.md.

## 26.10.2026 — test it, debug it, finish it

> "go to the camera and test it and debug it and improve it, see what's left with the features and make it perfect" (Marko, 26.10.2026)

76. A full pass over the camera on the Pixel 7: every feature tried from the Mac, the trace read for faults, what is still open listed and built. — **Status:** v148, found and fixed:
    - **The 10-bit GPU stage never came up** (every open in seven traces of 26.10: "no RGBA1010102 recordable config — the direct path is used"), so there was no tracking, no turned stream and one bit rate, and nothing said so on screen. The Pixel 7 (Android 17) offers RGBA1010102 only without EGL_RECORDABLE_ANDROID. v148 asks four configs in order and writes the one taken ("gpu stage config: RGBA1010102, window+pbuffer"). PROVEN: GPU stage ready in 10-bit HLG; a 4.3 s take = 130 frames at 30 fps, HEVC Main 10, yuv420p10le, BT.2020 arib-std-b67, upright, clean picture (ffprobe and a frame pulled on the Mac); AF → TRK starts the tracker (best match 0.973).
    - When the GPU stage cannot start, the status line says so ("GPU STAGE could not start: direct path, no tracking").
    - **16 KB pages**: libndi_bridge.so linked with -z max-page-size=16384 (libndi_advanced.so was already 0x4000); zipalign -c -P 16 passes; Android's "This app isn't 16 KB compatible" dialog no longer shows.
    - **The lens is remembered**: it always opened on L1, the fixed-focus ultra wide; now on the one it was left on (proven: L2, force-stop, reopen → L2, FOC: 21.4 cm).
    Still open: 64–66 (the remote over wireless ADB, scrcpy's picture), 65 (the wireless debugging guide), 70–71 (the focus puller's meter).

> "yes, please start the remote" (Marko, 26.10.2026, to 64–66: the remote over wireless ADB with scrcpy's picture, the ADB switch and status line, the guide)

77. The remote over ADB is started (64–66, 65, and the meter on the remote, 70). — **Status:** v150 released; see 64, 65, 66, 71. The Pixel's Wireless debugging now lists "Mantra Monitor (sdk_gphone64_arm64)", the emulator, as a paired device (revocable there).

> "by the way, please release the next version with the icon in the same style as the ttt advanced keyboard icon" (Marko, 26.10.2026)

78. The next version's icon in the style of the TTT Advanced Keyboard's icon. — **Status:** v151: first five lit keys (Marko: "Icon is not good"), then 86.

> "the app is open. Remote controller for camera on my Note phone. You can access it through wireless debugging" (Marko, 26.10.2026)
>
> "please open both phones in two views on the screen copy on my Mac" (Marko, 26.10.2026)

79. On the real remote, the Nothing Phone 2a (A142, Android 16, Mantra Monitor v150): PROVEN 26.10.2026 06:57 — paired with the Pixel by code (205942, port 33821) through the Mac's wireless adb, then "ADB ENABLED · Pixel 7 · 192.168.1.101:43917 · 30 fps · 0.7 Mbit/s", the camera's whole screen with its meter. Both phones open in scrcpy on the Mac ("PIXEL 7 · CAMERA", "NOTHING · REMOTE"). Found: the Nothing had kept "LINK 192.168.1.102", its own address, as its source, and kept retrying it.

> "in the app, the white triangle for focus pulling needs to have the white finger landing thing circle as the yellow one. Same in this. That means manual focusing: tapping inside it and dragging it up and down to make it focus. To leave the keyframe, it's a long press, and then the dialog opens: Add Keyframe or Delete Keyframe. All other keyframes there also have a long press on this circle, and then the user can delete the keyframe" (Marko, 26.10.2026)

80. The lens's white triangle gets a white finger circle like the key points' amber ones: press inside it and drag up and down to focus by hand. A long press on the white circle opens ADD KEYFRAME / DELETE KEYFRAME (delete when a key point stands there); a long press on any key point's circle offers DELETE KEYFRAME. (A tap on a key circle still racks to it; key points are no longer added by a tap on the scale.) On the camera and on the remote alike (the remote shows the camera's own meter). — **Status:** v151 released: the white circle drawn and dragged (seen in the trace, 6.4 → 7.0 dpt under a finger).

> "now we need to fix the user interface. The round button, the record button, should still be the record button as before. But this triangle, which is the playing button, should become this round button with the last picture of the last clip. Then when I press it, it plays. We don't have any more square buttons. We don't have any more triangle buttons. Everything is circular. Please make the recording in the place of the lot, in the middle of the screen" (Marko, 26.10.2026)

81. REC is the plain record button again (white disc, red while recording). The PLAY key (a triangle) becomes a round button showing the last capture; a press plays it (a take in the player, a still in the viewer). No square or triangle buttons on the rail: round. REC moves to the LUT's place, the middle of the rail; the LUT key takes REC's old place. — **Status:** v151 released, seen on the Pixel: LUT · the round PLAY with the last picture · SNAP · PORTRAIT · REC (white, the middle) · PEAK · FALSE · ZEBRA · free space. A still, then PLAY → Android's "open with" for the PNG (more than one viewer on the Pixel; Android asks once).

> "long press on the focusing pulling interface should last two seconds" (Marko, 26.10.2026)

82. On the focus puller's meter a long press is two seconds (the white circle's ADD / DELETE KEYFRAME, a key point's DELETE). — **Status:** v151.

> "add a keyframe in the pop-up menu. It should be some kind of button so people understand that they need to press the button. Now it looks like normal text, not like an action button" (Marko, 26.10.2026)

83. In the meter's pop-up, ADD KEYFRAME and DELETE KEYFRAME are buttons that look pressable (the app's outlined key style), not lines of text. — **Status:** v151.

> "actually, Cancel looks like a button because it has a different color. Add Keyframe can be the same style" (Marko, 26.10.2026)

84. (Replaces 83's look.) ADD KEYFRAME and DELETE KEYFRAME are the pop-up's own buttons, in the same colour and style as CANCEL. — **Status:** v151.

> "we need to have Add Keyframe and Remove Keyframe, both options. It's the keyframe column, and then two yellow buttons: Add or Remove" (Marko, 26.10.2026)

85. (Replaces 83 and 84.) The pop-up reads "KEYFRAME:" with the distance, and always two yellow buttons, ADD and REMOVE. ADD puts a keyframe where the lens is; REMOVE takes away the keyframe pressed (a key point's circle) or the one where the lens stands (the white circle), dimmed when there is none. A tap outside closes it. — **Status:** v151.

> "Icon is not good. Bring the camera back with the same color styling. That's all" (Marko, 26.10.2026)

86. (Replaces 78's keys.) The icon is the camera again (v95's traced camera), drawn in TTT's colours: the dark #0D1117 ground, the camera as lit neon lines with TTT's soft glow, the version under it. The monitor keeps its own old symbol (a screen with a lens) in the same colours. — **Status:** v151 released, seen on both phones' App info: the camera's amber outline, red flash tab, cyan lens ring with its glow, the body in TTT's key colour on #0D1117, v151 under it; the monitor's cyan screen with the green lens and a red REC dot. scripts/make_icons.py writes both.

## 26.10.2026 — the testing ground: stress, outputs, the remote

> "now continue testing. Search for the bugs, optimize the code, test all the outputs. You can control my web browser. You have NDI Monitor. You have OBS. Everything is here. The testing is ready. This is the testing ground. Please stress test the application. Remote controller, search for bugs and optimizations of the code" (Marko, 26.10.2026)

87. A stress pass: every output (FILE, NDI HX and FULL, over Wi-Fi and the cable) received on the Mac (NDI Monitor, OBS), the remote over ADB and over MANTRA LINK under load and reconnects, the code read for bugs and waste. — **Status:** in work.

88. BUG found by the stress pass, 26.10.2026: with NDI HX streaming on L2, `set ndiKind 2` (FULL) answered "Full NDI is not possible on this lens" and the HX stream stopped with it; the Mac's probe then found no source at all. A choice the lens cannot take must leave the stream it had running. — **Status:** v152: FULL that the lens refuses (10-bit takes the extra output) falls back to HX, said once (it was said every 2 s by the wire watch); PROVEN with the probe on the Mac: ndiKind=2 → HX streaming, 30 s, sound 99.87 %, longest gap 120 ms. NDI HX over Wi-Fi, 120 s before it: 30 fps 1080×1920, sound 99.98 %, the six late seconds (137–314 ms) carried picture and sound together (Wi-Fi bursts, the ADB remote on the same Wi-Fi).

> "already developed controlling of this Oxi device A16 over the USB in my computer, so you can animate the LED rings if you want to have some kind of moving picture. You can check for the drop frames or whatever you need to check when the picture is not static. If it's not developed, then you need to develop it." (Marko, 26.10.2026; the Oxi E16 in front of the lens)

89. A moving picture for the stress pass: the Oxi E16's LED rings animated from the Mac over USB MIDI, so dropped frames show in a picture that is not static. — **Status:** done 26.10.2026: ~/Developer/OXI_CONTROLLER tools/e16wave.swift (CoreMIDI, CC 1-16 on USB3, channel 2, a sine around the rings at 30 Hz), the rings seen moving through the camera. A 22.5 s take with the wave and NDI HX to the Mac at once: 676 frames, every gap 33.3 ms (none over 40 ms), 656 frames different from the one before (the 20 others the slow tops of the wave); NDI over the same time: sound 99.82 %, longest gap 267 ms. Also found and fixed in v152: the status line said NDI FULL while the fallback streamed HX (it named the setting, not the stream).

> "use ADB to disable logging off and password on my Pixel, or at least guide me to the screen I can press. This is a test phone. My Pixel. No passwords here for now for testing. Always on screen password can stay, but make screen always on, never logging off. If an app is open, it should keep the screen on, our camera app, right? Is that right" (Marko, 26.10.2026)

90. The Pixel 7 is a test phone: the screen always on, never locking itself; no PIN for testing (he removes it, it needs his PIN). The camera app keeps the screen on while it is open. — **Status:** in work.

91. THE CODE REVIEW (26.10.2026, an independent read of today's code: 13 findings) — fixed in v153: the remote asks the camera phone's address again before every try (a new wireless-debugging port is taken, not a dead one retried forever); "ADB ENABLED" only while the picture comes (a `connected` flag), BACK leaves the monitor when the link is down; the ADB key made once, synchronised and cached (pairing and connecting together could make two); touches keep the session packet's size (a decoder's padded 1088 would have made scrcpy ignore every touch); stop and close off the main thread, the control thread shut down, the certificate stream closed, `running` checked between steps, the scrcpy jar read once; any focus action ends an adb or meter rack, and a hand pull, a rack or a mode change ends a tap's walk; Settings.set gives "" (not null) to non-nullable text settings; REMOVE takes the keyframe by its value. Left: the Updater's "no network" for any failure, the GPU stage's 8-bit config pick on drivers that sort 10-bit first.

92. OPEN, found by the stress pass 26.10.2026 07:40: after `set ndiKind 1` while NDI HX streamed, the camera reported every frame sent (queued 125 sent 125, none dropped, parameter sets re-sent, a keyframe asked) but the Mac's probe decoded no picture for 3 minutes while the sound arrived — the "picture stops, sound goes on" of 9.10. The soak's later phases were spoilt by the screen lock (the Pixel now never locks). To reproduce next: HX streaming → set ndiKind 1/2 → probe. — REPRODUCED 26.10.2026 13:25 on a full battery (ndi_soak, v155): 3 min HX and five turns clean, at the 6th turn the phone went on sending every frame (queued = sent, 30/s, watching 1) and the Mac decoded none, the sound 100 %, never healing. Cause: rebuildStreamEncoder() cleared the parameter sets (parameterSets = null, NdiSender.clearVideoInfo()) AFTER starting the new encoder, which announces its own exactly once from its own thread; when it was quicker, the new parameter sets were wiped. v156: cleared before the start, only the newest encoder's callbacks count, and a keyframe with no parameter sets known takes them from the codec (HdrVideoEncoder.currentParameterSets) so a loss heals at the next keyframe. ndi_soak: --turns N. Being verified with 20 turns.

## 26.10.2026 — the camera from anywhere: the remote through the tunnel

> "it's okay. Just change iTerm settings instead of building a Swift app for this. Continue. Finish testing my app. I'm going to take my phone, and you can test it while I'm away. You can test the app, and if remote is viewing, it's actually working over a tunnel. Can I tunnel my remote controller to my application? No. This is the next feature: we can control this camera from anywhere in the world using the tunnel. Both sides need to accept the tunnel in the website. You're going to write which application we are installing: a free VPN. Then, through the VPN, I can remotely control this camera from anywhere in the world. There is no distance anymore. Distance is not the problem" (Marko, 26.10.2026)

93. The remote (Mantra Monitor on the Nothing) drives the camera (the Pixel) from anywhere through the tunnel: a free VPN (Tailscale) on both phones, both accepted on its website; over the same Wi-Fi it goes straight, away it goes through the tunnel; on mobile data the picture is lighter so the SIM is not wasted. Tested while Marko is away with the Nothing. — **Status:** v154 built (not released yet). The Pixel joined Tailscale as pixel-7, 100.70.23.53, adb on TCP 5555. The remote's TUNNEL key (or `--es tunnel 100.70.23.53:5555`) stores the address; with the VPN up the link uses it. PROVEN 26.10.2026 08:24–08:27: the Nothing on Telemach 4G+ (Wi-Fi off) showed the camera live through the tunnel, "ADB ENABLED · Pixel 7 · 100.70.23.53:5555 · 17 fps"; inside the tunnel the link even survives Wi-Fi -> 4G without a break. Fixed on the way: a lost SYN waited the system's 75 s (picture back after 77 s) -> a 4 s probe first; the heavy Wi-Fi picture kept on 4G -> the once-a-second tick restarts the picture at 2 Mbit/s, 1280 px, 24 fps when mobile data holds 3 s (and back to 8 Mbit/s on Wi-Fi); with the screen off Android blocks the app's network and the link failed every 6 s -> the link rests while hidden and comes back on wake. Waiting for Marko (the Nothing is locked): open the remote outside and see the picture come back on its own.

94. "please also make screen copy work over tunnel, so my phone is still on the screen when I'm away" (Marko, 26.10.2026) — **Status:** `nothing-screen` (MANTRA_REMOTE/scripts, on the PATH): scrcpy on the Nothing's tunnel address 100.67.149.40:5555, light (900 px, 15 fps, 2 Mbit/s), reopened by itself 3 s after any break (tested: killed, back in 3 s). `nothing-screen stop` ends it.

> "After this update, I can debug my remote app over ADB through the tunnel." / "The new feature is resolution scaling, in fractions of 0.9, 0.8, 0.7, 0.6 and 0.5. It saves bandwidth with the new setting, and the status line shows the new number." (Marko, 26.10.2026)

95. Resolution scaling for the remote's ADB picture: 1.0, 0.9, 0.8, 0.7, 0.6, 0.5 of the picture's size; a smaller picture costs less bandwidth; the status line shows the scale. — **Status:** v154: a tap on the status line steps 1.0 → 0.5 → 1.0; size × scale, bit rate × scale². Measured at 0.7 through the tunnel: 1120 px, 3.9 Mbit/s (was 1600 px, 8 Mbit/s). The tap itself not yet tested on the phone.

> "to the camera remote you can add the black and white screen, same as we have in our remote app" (Marko, 26.10.2026)

96. The black-and-white screen of Mantra Remote in the camera remote's ADB picture. — **Status:** v154 built and installed on the Nothing: a long press on the status line turns the picture black and white on the phone (a colour matrix on the picture's layer), the status line ends "· B/W"; `--ez bw true` for tests. scrcpy's H.264 stays colour, so it is the look, not less data (the scale is what saves data). Not yet seen on the phone: Marko was using it.

> "The remote camera quality must be set in the remote camera application. We have different resolutions that are transferred over the internet, Tailscale resolution and the color depth." / "Just copy this app I'm in right now. I have two names, Camera Remote and Computer Remote." / "You need to synchronize the quality features to make this usable on slow networks." (Marko, 26.10.2026, while in Mantra Remote)

97. The camera remote takes Mantra Remote's quality controls, the same keys and the same status line: FPS (tap steps, long press the list), colours (FULL … B/W), SCALE 1.0–0.5, set in the camera remote itself, so it works on slow networks. The two apps are named Camera Remote (Mantra Monitor) and Computer Remote (Mantra Remote). — **Status:** v154 built, installed on the Nothing (not released): while a camera is watched over ADB a rail at the bottom shows WI-FI/4G · FPS (30 24 15 10 5 2 1) · SCALE (1.0–0.5) · MBIT (8 4 2 1 0.5 0.25) · B/W; a tap steps, a long press opens the list; each network keeps its own choice (4G starts 15 FPS, 0.7, 1 Mbit). The status line: ADB · device · 4G · MB · kbit/s · fps of chosen · SCALE · MBIT · B/W. The Mac's colour depth has no meaning for H.264, so MBIT stands where the colours stand. The status-line tap/long press of item 95/96 replaced by the rail. Launcher name: Camera Remote; Mantra Remote's: Computer Remote (its v14 built, not installed while Marko was in it). Not yet seen on the phone.

> "When I say scale, I always mean scaling the resolution of the transfer of the file or the picture over VPN. I never mean rescaling the user interface itself, only the resolution of the transfer." (Marko, 26.10.2026)

- So it is built: SCALE sets scrcpy's max_size (the picture sent over the tunnel) and the bit rate; the remote draws what arrives at full size. The picture is smaller on screen only because v154 fits it between the status line and the rail (so they do not cover the camera's keys), the same at every SCALE.

> "continue testing and debugging the camera" (Marko, 26.10.2026)

98. Testing and debugging go on: FULL shows the focus-puller scale (seen in the screenshots), the NDI picture stall after an HX restart (item 92), then v154. — **Status:** FULL fixed (v154: the focus-puller scale and its play key hide on a clean feed; checked on the Pixel). NDI test 09:39–09:42 with ndi_probe.py: HX ran at ~30 fps but with frame gaps of 120–290 ms and SOUND GAP every second; `set ndiKind 1` while HX did nothing (same value, no restart); `set ndiKind 2` (FULL): 12 s nothing, 18 s FULL, then PICTURE STALL 9 s (sound on, no frames), then nothing. BUT the Pixel switched itself off at 09:43: `sys.boot.reason=shutdown,battery`, level 0 % while on a 5 V charger (AC powered, max 5 000 000 µV): torch 100 %, 10-bit, NDI, two remotes drew more than it gives. The stall and the gaps of this run are not evidence until they are repeated on a full battery and a fast (PD) charger.

> "Please, in my camera app, my manual camera, the best app on this planet, build a feature called power monitor. We now have two points of reference that tell me how long I can keep working. We already have the number for how much space I have left on my SD card, calculated in minutes depending on the current settings. But we also need battery minutes, meaning how long I can keep running like this until my battery dies. If I see this while I'm working on my computer, now that I'm remote, I will be able to predict a shutdown. So please make this feature robust and true to life. Find on GitHub the most forked APK repository that monitors the charging of the phone. They have good code to monitor everything. On my phone I have the Ampere application, which is very good. If it's open source, great, we take the code. If not, I'm sure somebody on GitHub made an application that shows how many amperes we get while charging the phone. So the battery left in hours and minutes is the new special feature of this app, the power monitor. If the phone is charging, it's the opposite: it shows how much time it needs to charge. It should show whether it's charging or discharging, plus or minus, or stable in balance. Please make this battery monitor a professional one, like the displays on batteries for Blackmagic cameras, V-mount batteries, where they show a number. I also have an Anker battery which, when I plug it into the phone, has a nice display. It's a circle with different colors, and it says how much time it needs to charge and how much time it needs to discharge. Maybe we can copy the Anker display." (Marko, 26.10.2026)

99. POWER MONITOR: battery time left in hours and minutes at the present draw (beside the drive's time left), time to full while charging; charging + / discharging − / balanced; the current in mA and the watts; a professional readout like a V-mount or Blackmagic battery, a coloured ring like Anker's; seen on the Camera Remote too, so a shutdown is predicted from afar. — **Status:** v155 built (not released). GitHub: the most forked charging app, BatteryChargeLimit (116 forks), is GPL-3.0 and needs root; Ampere is closed; Capacity Info (Apache-2.0) was read for the traps (µA or mA, either sign). PowerMonitor.kt: the unit by size, the sign from the cell's charge counter, the honest drain = the counter's least-squares slope over the last 2–10 minutes (the smoothed current now until then), a counter that implies a cell outside 1 000–15 000 mAh not believed (the level × the design capacity instead), the window reset when a cable goes in or out; to empty = charge ÷ drain; to full = Android's estimate, else missing ÷ net with the part above 80 % at half the current; BALANCED within ±50 mA. PowerRingView.kt beside the drive's readout (1.2 GB · 6 min): an Anker-like ring of the level (green > 50 %, amber, red < 20 % or < 20 min, cyan charging), the time big in the middle, ▲/▼/= and the current (−900mA, +1.2A) under it; a tap: the POWER MONITOR in words (time, %, mAh, mA, V, W, plugged, °C, measured or first estimate). `mcam state`: power, power_level, power_flow, power_ma, power_w, power_time, power_measured. 7 unit tests. Seen on the emulator: 64 % −900 mA → 3:05 to empty (green); charging → 2:42 to full (cyan). Not yet on the Pixel (locked).

> "this anchor display doesn't work because its display is too small. I don't see anything. You need to make it the same as free space, the same design, maybe in the blue color, so we make it different, and to save on space, what you need to do is make shorter. This portrait peak falls zebra. Everything: three letters, P-O-R, peak, P-E-A, falls, F-A-L, zebra, Z-E-B" (Marko, 26.10.2026)

100. The POWER MONITOR as a readout like the free space (two lines, large), in blue; the right rail's words in three letters: POR (LAN), PEA, FAL, ZEB. — **Status:** v155 on the Pixel: the ring is gone; the readout is a rail key like the free space, blue (#4DB8FF): "27%▲" over "1:29 h" (▼ discharging with the time to empty, = BAL, FULL), red under 20 % or 20 min, a tap the POWER MONITOR's numbers. POR/LAN, PEA, FAL, ZEB; `mcam key FALSE` and the other old names still reach them.

> "long press of any of the action buttons after 2 seconds. Open the help menu explaining what the button does" (Marko, 26.10.2026)

101. A 2-second press on any key of the camera opens its help: what the key does. — **Status:** v155: every rail key, REC and PLAY (Help.kt, one text per key, the POWER MONITOR's with the reading now); the held key is not pressed; LUT keeps its own long press (settings). Tested on the Pixel: PEA held 2.6 s → "PEA · PEAKING", peaking still off.

> "for false color, not VEL. Make it FC" (Marko, 26.10.2026)

102. The false-colour key reads FC (not FAL). — **Status:** v155, then an icon (104).

> "instead of POR, make a standard SVG icon, same as the camera there, just change the orientation, kind of thing. What symbolizes the change of orientation of the screen should be there" / "also, zebra can be simple. You make one square and inside lines in some kind of angle. It can be like zebra" (Marko, 26.10.2026)

103. POR/LAN as an icon in the camera icon's line style: a phone with a turning arrow, upright or lying across; ZEB as a square of slanted lines. — **Status:** v155: ROTATE (redrawn in 106), ZEBRA (a square of slanted lines) as RailButton glyphs; the keys keep their names (POR/LAN, ZEB) for mcam and the remote.

> "Peaking will be the red square with random dots" / "false color are patches of the same color. In false color, it usually looks like patches" (Marko, 26.10.2026)

104. PEA: a square with red dots; FC: a square of false-colour patches. — **Status:** v155 on the Pixel; outlines grey off, green on.

> "now the numbers of discharging and charging are changing too rapidly. You need to average the value and don't and just make it less fast" (Marko, 26.10.2026)

105. The POWER MONITOR steadier: averaged, refreshed less often. — **Status:** v155: the current smoothed 3× slower (α 0.05), the time shown follows over about a minute, the readout written every 5 s (at once when the flow turns); while charging the time is made of the cell's measured net charge once there (Android's estimate assumes an idle phone: 1:34 then 8:33).

> "Icon for changing rotation is not good. It should be just two squares, one vertical, one horizontal, overlapping. Forget the phone, make simple square design Not square rectangle." (Marko, 26.10.2026)

106. The orientation icon: two overlapping rectangles, one upright, one across; no phone. — **Status:** v155: the shooting one bold, the other faint.

> "please make the color of the camera in the same row icon in a different color. Make it a yellow-orange color. Make it orange" / "On the long press on the False colors, please make legend what each color means in exposure values." (Marko, 26.10.2026)

107. The photo camera icon orange; the FC help a legend: each false colour and its exposure value. — **Status:** v155: the camera #FF9500; FC held 2 s: PURPLE < 2.5 % (≈ −6 EV), BLUE 2.5–10 % (≈ −4), GREEN 38–42 % (0 EV, middle grey), PINK 52–56 % (+1, skin), YELLOW 97–99 % (≈ +4), RED > 99 % (clipped), GREY elsewhere — the shader's own bands (PreviewEffects.kt); a note that on log curves grey sits lower.

> "so, please let me know if logic for battery is correct. When the triangle is going up, it means we are charging and the number shows the charging state. That color of the everything should be green means we are charging. When we are discharging, we are yellow, and the number shows how much time we have left to discharge. When we are balanced, then we are white and average values for time, what you calculate every five seconds" (Marko, 26.10.2026)

108. The POWER MONITOR's colours: ▲ charging green (time to full), ▼ discharging yellow (time left; red when critical), = balanced white (the averaged time, every 5 s). — **Status:** v155 on the Pixel: 28%▲ 2:06 h in green while charging; yellow discharging, red under 20 % or 20 min; white when balanced, the time of its slow drift (BAL when still within 5 mA).

> "now I wanted you to do this screen orientation thing. You need to make the icon also orange, same as the camera recording button. Make the outline in red color, please" (Marko, 26.10.2026)

109. The orientation icon orange like the photo camera; the REC button's outline red. — **Status:** v155 on the Pixel.

> "outline for the video player. The one with the thumbnail inside should be green" (Marko, 26.10.2026)

110. PLAY (the thumbnail key): its ring green. — **Status:** v155.

> "Orange should be the theme of this app. Then, around false color peaking and zebra, put an orange outline" (Marko, 26.10.2026)

111. Orange is the app's theme; FC, PEA and ZEB with an orange outline (green while on). — **Status:** v155 on the Pixel (orange #FF9500 for the photo, orientation, FC, PEA, ZEB keys; RailButton.onGreen keeps ON green).

> "inside of the recording button, let it be black. When it's record, it's turn red. When it's not recording, it's black" (Marko, 26.10.2026)

112. REC: black inside, red while recording (the red ring stays). — **Status:** v155 on the Pixel. Also: a first power estimate waits 30 s after the app opens or a cable changes (0:18 h was the work of starting the app); seen: the Pixel reports status 4, NOT CHARGING, on AC at 35 °C (Android paused charging), shown as ▼/balanced.

> "make zebra lines gray, but outline is orange. Picking outline orange. Okay, understand. When it's activated, it's green. Only change the zebra to have these lines on the angle in the color of gray" (Marko, 26.10.2026)

113. ZEB: the slanted lines grey, the outline orange (green when on). — **Status:** v155.

> "okay, we can pose this project back to the camera, test all its functions" (Marko, 26.10.2026)

114. Every function of the camera tested (v155): keys, settings, recording, photos, lenses, curves, focus, NDI, the power monitor, the remote. — **Status:** in work. field_test.py over Wi-Fi: first 8/23 — the script started `.MainActivity` (wrong package: -S stopped the app, the start failed, every check read a dead trace; the same in ndi_soak.py), fixed → 20/23. Left: AUTO to a USB-C mic (none plugged: not a bug); FULL streamed as HX (the session "everything, 10-bit" refused, the ladder keeps 10-bit and drops full NDI, by design since v136; Marko to choose when FULL is asked); the REC key not found (Android 17's uiautomator gives "null root node" over a live camera → the test presses REC through mcam). REC by hand: 10.7 s take, HEVC 1920×1080 10-bit HLG, AAC 48 kHz mono, 322 frames. The pixel7 sharing lock is gone from this Mac: the tests go on unless com.djmantra.app runs.
- 26.10.2026 13:35 soak on v156 void: the 13:20 soak's screen-off phase had locked the Pixel (its PIN is still set), the next run started behind the lock screen, the camera came up only at 13:41 when Marko unlocked it, and the probe had given up after 20 s ("watching 0" throughout). To repeat unlocked, without screenoff (or after the PIN is removed).
- 26.10.2026 14:35: Marko removed the Pixel's PIN (Settings → Security & privacy → Device unlock → Screen lock → None); confirmed CredentialType: NONE.

## 27.10.2026: the camera into Zoom through NDI Virtual Input over USB tethering

> your task this morning is to first install on my computer the NDI program, which is free on NDI pages, which can convert NDI to webcam. Then, through USB tethering, our camera, which is now on emulator on Pixel 7, should send the image to that plugin. I have it as Zoom in Zoom call. I have it as webcam. That's the deal. Please make it work. It's a simple task from camera to NDI plugin. I need a program for webcam through USB tethering. Everything is connected. Please make it work. You need to download that from NDI I have it installed, but it's not installed. Please take the screenshot. You will understand what I'm talking about.

**Status:** 27.10.2026, started. NDI Tools 6.3.2 are installed; the NDI Camera Extension is "activated waiting for user" (macOS approval, Marko's hand). USB tethering up: Mac en19 172.22.112.50, Pixel ncm0 172.22.112.72.

**Later the same morning (27.10.2026), while the work ran:**

> Please experiment with 8-bit and 10-bit output from the camera. But both must work: 8-bit and 10-bit, at the end.

> You always need to check if the feed is right or the image is frozen. The image should not be frozen.

> When you are testing if the image is frozen, you can ask me to wave the hand.

> Also test if the camera is working when it's in the background.

> Also, by the way, delete all keyframes from the focus polling. I tried to press with the mouse or screen copy to get a pop-up menu to delete keyframes, and nothing popped up. Maybe that's also the bug, by the way.

> The goal is to get the same picture which I have on the Pixel 7 emulator screen copy right now, and what's coming through NDI. Please compare. You can take a screenshot of the Pixel 7 which is right here on my computer, and with NDI virtual input. If there's a difference, then it's not good. Please make it the same.

**Found so far (v157, not released):** 10-bit HX froze in Zoom: NDI Virtual Input decodes no HEVC Main10. v157 draws the stream 8-bit SDR (HLG BT.2020 to BT.709) in its own RGB888 context; 8-bit and 10-bit both live over USB. In the background the sender is made again and fails ("NDI source could not be opened"): being fixed.

**Status 27.10.2026, 05:51: done, v157 released and installed.** Zoom gets the camera over the cable (NDI Virtual Input,
source LOCALHOST (Pixel 7 Camera)); 8-bit and 10-bit both live, every curve, the background, and Marko's hand wave.
Screen, clean still and NDI match (still vs NDI 0.4–3.7/255 in the log curves and 8-bit, 3–7.5 in HLG). WB on the
white paper, neutral. Focus key points deleted. The still now comes out the right way up.
