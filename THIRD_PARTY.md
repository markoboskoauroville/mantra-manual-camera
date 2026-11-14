# Third-party code in Mantra Manual Camera

| What | Where | Licence | Used for |
|---|---|---|---|
| libmv (TrackRegion and the parts it needs), from Blender's `intern/libmv/libmv`, one line adapted to Eigen 3.4 | `app/src/main/cpp/third_party/libmv` | MIT (`LICENSE.txt` there) | the motion tracker (TRK), since v160 |
| Ceres Solver 2.2.0 (no tests, examples, docs, data) | `app/src/main/cpp/third_party/ceres` | BSD-3-Clause (`LICENSE` there) | libmv's Levenberg-Marquardt solve |
| Eigen 3.4.0 (headers), built with `EIGEN_MPL2_ONLY` | `app/src/main/cpp/third_party/eigen` | MPL-2.0 (`COPYING.MPL2` there) | the maths under both |
| USB Video Class reader (v193), written here | `app/src/main/java/com/mantraproductions/ndi/uvc` | the app's own | the Sony (any bulk UVC camera) as a lens. No libusb/libuvc: the Sony ILCE-7M5 streams on a BULK endpoint, so Android's UsbDeviceConnection does it all; the standard JPEG Huffman tables (ITU T.81 Annex K) are public data |
| NDI Advanced SDK | not in the repository (`mantra-ndi-sdk`, private) | NDI SDK licence | NDI |

Blender's own glue and interface code and Natron are GPL and are not used; the tracking interface follows Blender's
design only. The research behind the choice: `~/Developer/TRACKER_RESEARCH/REPORT.md` on the Mac.
