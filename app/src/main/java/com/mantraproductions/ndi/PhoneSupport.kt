package com.mantraproductions.ndi

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * v177, WHAT THIS PHONE CAN DO (Marko, 29.10.2026: "Please detect if this app is installed on a different phone. Then,
 * if the user presses something that the app is not able to do for that phone, in particular, you need to write on the
 * screen 'Not supported feature on this phone.' ... Find what other cases should be so it works across the different
 * phones.").
 *
 * Read once per lens from what the camera itself publishes (no list of phone models): a key that asks for something
 * the lens or the phone does not have says [SENTENCE] and does nothing, instead of trying and falling back quietly.
 */
object PhoneSupport {
    const val SENTENCE = "Not supported feature on this phone"

    data class Lens(
        val tenBit: Boolean,          // HLG 10-bit from the camera AND a 10-bit HEVC encoder
        val manualExposure: Boolean,  // ISO and shutter by hand (MANUAL_SENSOR)
        val manualWhiteBalance: Boolean, // a white balance by numbers (MANUAL_POST_PROCESSING)
        val focusTravel: Boolean,     // a lens that focuses (minimum focus distance above 0)
        val torch: Boolean,           // a lamp beside this lens
        val exposureCompensation: Boolean, // EV steps
        val toneCurve: Boolean,       // the log curves need the camera's tone curve
    ) {
        /** The features this lens lacks, in words, for the trace and the first-start sentence. */
        fun missing(): List<String> = listOfNotNull(
            "10-bit".takeUnless { tenBit },
            "manual exposure".takeUnless { manualExposure },
            "manual white balance".takeUnless { manualWhiteBalance },
            "focus (fixed-focus lens)".takeUnless { focusTravel },
            "torch".takeUnless { torch },
            "exposure compensation".takeUnless { exposureCompensation },
            "log curves".takeUnless { toneCurve },
        )
    }

    private val cache = HashMap<String, Lens>()

    /** [key] is the lens as the app names it, "id" or "id:physicalId". */
    fun lens(context: Context, key: String): Lens? = cache[key] ?: runCatching {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = key.substringAfter(':', key.substringBefore(':'))
        val c = manager.getCameraCharacteristics(id)
        of(c).also { cache[key] = it }
    }.getOrNull()

    fun of(c: CameraCharacteristics): Lens {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        val hdr = HdrCapabilities(c)
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        return Lens(
            tenBit = hdr.supportsHlg10 && hevcMain10,
            manualExposure = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            manualWhiteBalance = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps,
            focusTravel = (c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f,
            torch = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            exposureCompensation = ev != null && ev.lower < ev.upper,
            toneCurve = hdr.supportsToneCurve,
        )
    }

    /** A hardware HEVC encoder that writes Main10 (10-bit HLG takes): without it 10-bit is a picture nobody can record. */
    val hevcMain10: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    }
            }
        }.getOrDefault(false)
    }

    /** Blender's tracker is a 64-bit ARM library; a phone that cannot load it has no TRK. */
    val tracker: Boolean get() = LibmvTracker.available

    /** The phone in words, for the trace and the first start on a new phone. */
    val phoneName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
