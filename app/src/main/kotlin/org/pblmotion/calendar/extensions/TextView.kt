package org.pblmotion.calendar.extensions

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import org.fossify.commons.extensions.addBit
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.removeBit

/**
 * Sets a resized and tinted drawable as the background of this [TextView].
 *
 * The drawable at [drawableId] is rasterised to a square of [drawableHeight] × [drawableHeight]
 * pixels, tinted with [primaryColor], and applied as the view background.
 *
 * @param res The [Resources] used to load the drawable.
 * @param drawableHeight Target height (and width) of the scaled bitmap in pixels.
 * @param primaryColor The tint color to apply.
 * @param drawableId The resource ID of the drawable to use.
 */
fun TextView.addResizedBackgroundDrawable(res: Resources, drawableHeight: Int, primaryColor: Int, drawableId: Int) {
    val baseDrawable = res.getDrawable(drawableId).toBitmap(drawableHeight, drawableHeight)
    val scaledDrawable = BitmapDrawable(res, baseDrawable)
    scaledDrawable.applyColorFilter(primaryColor)
    background = scaledDrawable
}

/**
 * Adds or removes [Paint.STRIKE_THRU_TEXT_FLAG] on this [TextView].
 *
 * Use this to visually indicate that an event or task is completed, cancelled, or declined.
 *
 * @param addFlag `true` to show a strike-through; `false` to remove it.
 */
fun TextView.checkViewStrikeThrough(addFlag: Boolean) {
    paintFlags = if (addFlag) {
        paintFlags.addBit(Paint.STRIKE_THRU_TEXT_FLAG)
    } else {
        paintFlags.removeBit(Paint.STRIKE_THRU_TEXT_FLAG)
    }
}

/**
 * Applies the visual style for a completed Taskify event to this [TextView].
 *
 * When [isCompleted] is `true`:
 * - Adds [Paint.STRIKE_THRU_TEXT_FLAG] to the paint flags.
 * - Optionally changes the text colour to [dimmedColor] (pass `null` to keep the current colour).
 *
 * When [isCompleted] is `false`, the strike-through flag is removed. Text colour is **not**
 * reset automatically — callers are responsible for restoring the original colour if needed.
 *
 * @param isCompleted Whether the associated event/task has been completed.
 * @param dimmedColor Optional dimmed text colour to apply when completed; `null` leaves the
 *   colour unchanged.
 */
fun TextView.applyTaskifyCompletedStyle(isCompleted: Boolean, dimmedColor: Int? = null) {
    if (isCompleted) {
        paintFlags = paintFlags.addBit(Paint.STRIKE_THRU_TEXT_FLAG)
        if (dimmedColor != null) {
            setTextColor(dimmedColor)
        }
    } else {
        paintFlags = paintFlags.removeBit(Paint.STRIKE_THRU_TEXT_FLAG)
    }
}

