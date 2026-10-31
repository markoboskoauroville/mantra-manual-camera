package com.mantraproductions.ndi.sony

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Holds the picture at its own shape and gives the rest back as black, where the keys live.
 * From mantra-ndi's AspectFrame. 16:9 until a source says otherwise (an A7 V's stills are 3:2).
 */
class AspectFrame @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    var aspect: Double = 16.0 / 9.0
        set(value) {
            if (value > 0.0 && kotlin.math.abs(value - field) > 0.001) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth <= 0 || availableHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val box = com.mantraproductions.ndi.Mechanism.pictureBox(availableWidth, availableHeight, aspect)
        setMeasuredDimension(box[0], box[1])
        measureChildren(
            MeasureSpec.makeMeasureSpec(box[0], MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(box[1], MeasureSpec.EXACTLY)
        )
    }
}
