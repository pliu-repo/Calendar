package org.pblmotion.calendar.views

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout

/**
 * Keeps click accessibility semantics when touch handling is attached dynamically.
 */
class ClickableRelativeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {
    override fun performClick(): Boolean {
        return super.performClick()
    }
}

