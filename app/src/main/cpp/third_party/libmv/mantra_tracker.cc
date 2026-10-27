// See mantra_tracker.h. The reference is a crop around the keyframe's pattern; each frame is searched in a crop
// around the predicted place (last centre + last velocity), so libmv's brute pass and its Levenberg-Marquardt
// refinement only ever see a small image.
#include "mantra_tracker.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include "libmv/image/image.h"
#include "libmv/tracking/track_region.h"

struct MantraTracker::Impl {
  int model; double min_corr; int search_half;
  libmv::FloatImage ref;              // the keyframe crop
  double rx[5], ry[5];                // the pattern in ref crop coordinates (4 corners + centre)
  double qx[4], qy[4];                // the quad now, frame coordinates
  double vx = 0, vy = 0;              // centre velocity, pixels per frame
  int lost = 0;                       // frames since the subject was last found
  bool started = false;
};

// cut (x0, y0, cw x ch) in frame coordinates out of a w x h crop placed at (ox, oy), [stride] bytes a pixel
static void Crop(const unsigned char* g, int stride, int w, int h, int ox, int oy, int x0, int y0, int cw, int ch,
                 libmv::FloatImage* out) {
  out->Resize(ch, cw, 1);
  for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) {
    int sx = std::min(std::max(x0 + x - ox, 0), w - 1), sy = std::min(std::max(y0 + y - oy, 0), h - 1);
    (*out)(y, x, 0) = g[(sy * w + sx) * stride] / 255.0f;
  }
}

MantraTracker::MantraTracker(int model, double min_corr, int search_half) : d(new Impl) {
  d->model = model; d->min_corr = min_corr; d->search_half = search_half;
}
MantraTracker::~MantraTracker() = default;

void MantraTracker::Start(const unsigned char* g, int stride, int w, int h, int ox, int oy, double cx, double cy, double half) {
  int m = (int)std::ceil(half) + 4;
  int x0 = (int)std::floor(cx) - m, y0 = (int)std::floor(cy) - m;
  Crop(g, stride, w, h, ox, oy, x0, y0, 2 * m + 1, 2 * m + 1, &d->ref);
  double kx[4] = {-half, half, half, -half}, ky[4] = {-half, -half, half, half};
  for (int i = 0; i < 4; i++) {
    d->rx[i] = cx + kx[i] - x0; d->ry[i] = cy + ky[i] - y0;
    d->qx[i] = cx + kx[i]; d->qy[i] = cy + ky[i];
  }
  d->rx[4] = cx - x0; d->ry[4] = cy - y0;
  d->vx = d->vy = 0; d->lost = 0; d->started = true;
}

void MantraTracker::SearchBox(int* x0, int* y0, int* x1, int* y1) const {
  double cx = 0, cy = 0, half = 0;
  for (int i = 0; i < 4; i++) { cx += d->qx[i] / 4; cy += d->qy[i] / 4; }
  for (int i = 0; i < 4; i++) half = std::max(half, std::max(std::fabs(d->qx[i] - cx), std::fabs(d->qy[i] - cy)));
  int s = (int)std::ceil(half) + d->search_half * (d->lost > 0 ? 3 : 1) + 2;
  double gx = cx + d->vx, gy = cy + d->vy;
  *x0 = (int)std::floor(gx) - s; *y0 = (int)std::floor(gy) - s; *x1 = (int)std::floor(gx) + s + 1; *y1 = (int)std::floor(gy) + s + 1;
}

MantraTrackResult MantraTracker::Track(const unsigned char* g, int stride, int w, int h, int ox, int oy) {
  auto t0 = std::chrono::steady_clock::now();
  MantraTrackResult r{};
  double cx = 0, cy = 0;
  for (int i = 0; i < 4; i++) { cx += d->qx[i] / 4; cy += d->qy[i] / 4; }
  // the guess: where the centre will be if it keeps its speed
  double gx = cx + d->vx, gy = cy + d->vy;
  double half = 0; for (int i = 0; i < 4; i++) half = std::max(half, std::max(std::fabs(d->qx[i] - cx), std::fabs(d->qy[i] - cy)));
  // lost: the search widens (x3) until the subject is found again; the brute pass covers the whole crop
  int s = (int)std::ceil(half) + d->search_half * (d->lost > 0 ? 3 : 1);
  int x0 = (int)std::floor(gx) - s, y0 = (int)std::floor(gy) - s;
  libmv::FloatImage cur;
  Crop(g, stride, w, h, ox, oy, x0, y0, 2 * s + 1, 2 * s + 1, &cur);
  double x2[5], y2[5];
  for (int i = 0; i < 4; i++) { x2[i] = d->qx[i] + d->vx - x0; y2[i] = d->qy[i] + d->vy - y0; }
  x2[4] = gx - x0; y2[4] = gy - y0;
  libmv::TrackRegionOptions o;
  o.mode = (libmv::TrackRegionOptions::Mode)d->model;
  o.minimum_correlation = d->min_corr;
  o.max_iterations = 50;
  o.use_esm = true;
  o.use_brute_initialization = true;
  o.attempt_refine_before_brute = true;
  o.use_normalized_intensities = true;
  o.sigma = 0.9;
  libmv::TrackRegionResult res;
  libmv::TrackRegion(d->ref, cur, d->rx, d->ry, o, x2, y2, &res);
  r.termination = res.termination; r.correlation = res.correlation; r.usable = res.is_usable();
  if (r.usable) {
    double ncx = 0, ncy = 0;
    for (int i = 0; i < 4; i++) { d->qx[i] = x2[i] + x0; d->qy[i] = y2[i] + y0; ncx += d->qx[i] / 4; ncy += d->qy[i] / 4; }
    double steps = 1.0 + d->lost;     // the frames this move took
    d->vx = 0.7 * (ncx - cx) / steps + 0.3 * d->vx; d->vy = 0.7 * (ncy - cy) / steps + 0.3 * d->vy;
    d->lost = 0;
  } else {
    d->lost++;
    if (d->lost > 2) d->vx = d->vy = 0;   // a guess that kept missing is no guess
  }
  for (int i = 0; i < 4; i++) { r.x[i] = d->qx[i]; r.y[i] = d->qy[i]; r.cx += d->qx[i] / 4; r.cy += d->qy[i] / 4; }
  r.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
  return r;
}
