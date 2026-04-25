package org.pblmotion.calendar.extensions

import org.pblmotion.calendar.helpers.Formatter
import org.pblmotion.calendar.models.Event

fun Long.isTsOnProperDay(event: Event): Boolean {
    val dateTime = Formatter.getDateTimeFromTS(this)
    val power = 1 shl (dateTime.dayOfWeek - 1)
    return event.repeatRule and power != 0
}
