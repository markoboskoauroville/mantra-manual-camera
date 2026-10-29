// mantra_tracker: libmv's TrackRegion (Blender's tracker, MIT) wrapped for Mantra Manual Camera (v160).
// One subject: a pattern quad cut from a keyframe, followed frame by frame with a constant-velocity guess.
#pragma once
#include <memory>

struct MantraTrackResult {
  double x[4], y[4];      // the quad's corners in the current frame, pixels
  double cx, cy;          // its centre
  double correlation;     // Pearson correlation with the keyframe's pattern, -1..1
  int termination;        // libmv::TrackRegionResult::Termination
  bool usable;            // converged and above the minimum correlation
  double ms;              // time taken
};

class MantraTracker {
 public:
  // model: 0 LOC, 1 LOC+ROT, 2 LOC+SCALE, 3 LOC+ROT+SCALE, 4 AFFINE, 5 PERSPECTIVE (libmv's order)
  MantraTracker(int model, double minimum_correlation, int search_half);
  ~MantraTracker();
  // grey frame (0..255 bytes), the pattern square centred at (cx, cy) with half size [half]
  void Start(const unsigned char* grey, int w, int h, double cx, double cy, double half) { Start(grey, 1, w, h, 0, 0, cx, cy, half); }
  MantraTrackResult Track(const unsigned char* grey, int w, int h) { return Track(grey, 1, w, h, 0, 0); }
  // v160: [px] is a w x h crop of the frame placed at (ox, oy), [stride] bytes a pixel (4: RGBA, grey in the first);
  // coordinates stay the frame's, so each frame may hand over a different crop.
  void Start(const unsigned char* px, int stride, int w, int h, int ox, int oy, double cx, double cy, double half);
  MantraTrackResult Track(const unsigned char* px, int stride, int w, int h, int ox, int oy);
  // where the next frame's crop must reach, so the search (widened while lost) lies inside it
  void SearchBox(int* x0, int* y0, int* x1, int* y1) const;
 private:
  struct Impl;
  std::unique_ptr<Impl> d;
};
