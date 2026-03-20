package org.pblmotion.calendar.helpers

import android.content.Context
import org.pblmotion.calendar.extensions.eventsHelper
import org.pblmotion.calendar.interfaces.WeeklyCalendar
import org.pblmotion.calendar.models.Event
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.WEEK_SECONDS

class WeeklyCalendarImpl(val callback: WeeklyCalendar, val context: Context) {
    var mEvents = ArrayList<Event>()

    fun updateWeeklyCalendar(weekStartTS: Long) {
        val endTS = weekStartTS + 2 * WEEK_SECONDS
        context.eventsHelper.getEvents(weekStartTS - DAY_SECONDS, endTS) {
            mEvents = it
            callback.updateWeeklyCalendar(it)
        }
    }
}
