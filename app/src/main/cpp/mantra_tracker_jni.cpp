// v160: the JNI side of the tracker (LibmvTracker.kt). One tracker per handle; the GPU stage's thread calls it.
#include <jni.h>
#include "mantra_tracker.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_mantraproductions_ndi_LibmvTracker_nativeStart(JNIEnv* env, jobject, jbyteArray grey, jint w, jint h,
        jdouble cx, jdouble cy, jdouble half, jint model, jdouble minCorrelation, jint searchHalf) {
    auto* t = new MantraTracker(model, minCorrelation, searchHalf);
    jbyte* g = env->GetByteArrayElements(grey, nullptr);
    t->Start(reinterpret_cast<unsigned char*>(g), w, h, cx, cy, half);
    env->ReleaseByteArrayElements(grey, g, JNI_ABORT);
    return reinterpret_cast<jlong>(t);
}

// out: cx, cy, correlation, usable (0/1), ms, x0 y0 x1 y1 x2 y2 x3 y3
extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_LibmvTracker_nativeTrack(JNIEnv* env, jobject, jlong handle, jbyteArray grey,
        jint w, jint h, jdoubleArray out) {
    auto* t = reinterpret_cast<MantraTracker*>(handle);
    jbyte* g = env->GetByteArrayElements(grey, nullptr);
    MantraTrackResult r = t->Track(reinterpret_cast<unsigned char*>(g), w, h);
    env->ReleaseByteArrayElements(grey, g, JNI_ABORT);
    double v[13] = {r.cx, r.cy, r.correlation, r.usable ? 1.0 : 0.0, r.ms,
                    r.x[0], r.y[0], r.x[1], r.y[1], r.x[2], r.y[2], r.x[3], r.y[3]};
    env->SetDoubleArrayRegion(out, 0, 13, v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_LibmvTracker_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<MantraTracker*>(handle);
}

// v160: straight from the GPU stage's read-back (a direct RGBA ByteBuffer of a crop at (ox, oy))
extern "C" JNIEXPORT jlong JNICALL
Java_com_mantraproductions_ndi_LibmvTracker_nativeStartRgba(JNIEnv* env, jobject, jobject rgba, jint w, jint h,
        jint ox, jint oy, jdouble cx, jdouble cy, jdouble half, jint model, jdouble minCorrelation, jint searchHalf) {
    auto* t = new MantraTracker(model, minCorrelation, searchHalf);
    t->Start(static_cast<unsigned char*>(env->GetDirectBufferAddress(rgba)), 4, w, h, ox, oy, cx, cy, half);
    return reinterpret_cast<jlong>(t);
}

// out as nativeTrack, then the next crop: x0 y0 x1 y1
extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_LibmvTracker_nativeTrackRgba(JNIEnv* env, jobject, jlong handle, jobject rgba,
        jint w, jint h, jint ox, jint oy, jdoubleArray out) {
    auto* t = reinterpret_cast<MantraTracker*>(handle);
    MantraTrackResult r = t->Track(static_cast<unsigned char*>(env->GetDirectBufferAddress(rgba)), 4, w, h, ox, oy);
    int b[4]; t->SearchBox(&b[0], &b[1], &b[2], &b[3]);
    double v[17] = {r.cx, r.cy, r.correlation, r.usable ? 1.0 : 0.0, r.ms,
                    r.x[0], r.y[0], r.x[1], r.y[1], r.x[2], r.y[2], r.x[3], r.y[3],
                    (double)b[0], (double)b[1], (double)b[2], (double)b[3]};
    env->SetDoubleArrayRegion(out, 0, 17, v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_LibmvTracker_nativeSearchBox(JNIEnv* env, jobject, jlong handle, jintArray out) {
    int b[4]; reinterpret_cast<MantraTracker*>(handle)->SearchBox(&b[0], &b[1], &b[2], &b[3]);
    env->SetIntArrayRegion(out, 0, 4, b);
}
