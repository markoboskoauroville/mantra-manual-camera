# Third-party code in Mantra Manual Camera

| What | Where | Licence | Used for |
|---|---|---|---|
| libmv (TrackRegion and the parts it needs), from Blender's `intern/libmv/libmv`, one line adapted to Eigen 3.4 | `app/src/main/cpp/third_party/libmv` | MIT (`LICENSE.txt` there) | the motion tracker (TRK), since v160 |
| Ceres Solver 2.2.0 (no tests, examples, docs, data) | `app/src/main/cpp/third_party/ceres` | BSD-3-Clause (`LICENSE` there) | libmv's Levenberg-Marquardt solve |
| Eigen 3.4.0 (headers), built with `EIGEN_MPL2_ONLY` | `app/src/main/cpp/third_party/eigen` | MPL-2.0 (`COPYING.MPL2` there) | the maths under both |
| NDI Advanced SDK | not in the repository (`mantra-ndi-sdk`, private) | NDI SDK licence | NDI |

Blender's own glue and interface code and Natron are GPL and are not used; the tracking interface follows Blender's
design only. The research behind the choice: `~/Developer/TRACKER_RESEARCH/REPORT.md` on the Mac.
