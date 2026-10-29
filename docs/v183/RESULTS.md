# v183 on the Pixel 7, 29.10.2026

Built on the Mac at c1ae7a6 (`scripts/release_mac.sh --no-release`: NDI inside, the permanent key, version 183), installed
with `adb install -r` on the Pixel 7 (41241FDH2S10SJ). Upside down was checked on the Pixel_7_API_35 emulator with the same
APK, the sensor set by `adb emu sensor set acceleration 0:-9.81:0` (the app takes the turn only from the accelerometer).

| Test | Result | Screenshot |
|---|---|---|
| a. CTRL under the ruler key, INFO where CTRL was | pass | a_rail_ctrl_under_ruler_info_below.png |
| b. Landscape: faders as before | pass | b_landscape_faders.png |
| b. Portrait: number above name at the far end, A/M and 0°–360° upright, BASE/HIGH unchanged, faders still | pass; 180°/270°/360° drawn smaller than 0°/90°; REC timecode does not turn | b_portrait_faders.png |
| b. Upside down: words turned over in place | pass (emulator) | b_upside_down_faders_emulator.png |
| c. Cap line across the travel, grip ridge either side | pass | c_fader_cap_zoom.png |
| d. Yellow line on manual ISO, SHTR, WB; stays while dragging; cap yellow on it | pass; dark room, both lines at the end of travel, AUTO gave ISO 744, so the AUTO comparison wants a lit scene | d_yellow_lines_iso_shtr_wb.png, d_drag_mid_line_stays.png, d_cap_yellow_on_line.png |
| e. Long press on the circle: ISO / WB / SHUTTER −/+ (and TRACK OFF); label ISO or WB | pass; the menu covers the REC timecode | e_circle_long_press_menu.png, e_circle_label_wb.png |
| g. Lens keys: green L2 25mm, L3 50mm; orange L1 17mm, L4 21mm, L5 24mm | colours pass; text FAIL: the "Ln" line is cut at the top and the "nnMP" line at the bottom on every key, and the in-use bar runs through "12MP" | g_lens_drawer.png |
| h. Top line "1280x720 · 8b · 19 Mb/s", no fps; "Focus locked" then back to the format within ~3 s | pass | h_status_line_portrait.png, h_focus_locked_then_format.png |
| f. Crashes (`adb logcat -b crash`) | none on the Pixel 7 or the emulator | — |

Also seen: with the lens drawer open the left rail moves to the screen edge; the repository tracks `monitor/build/`, so
every build leaves about 110 tracked files changed.
