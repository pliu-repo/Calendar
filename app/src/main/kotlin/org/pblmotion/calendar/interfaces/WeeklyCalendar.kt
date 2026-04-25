package org.pblmotion.calendar.interfaces

import org.pblmotion.calendar.models.Event

interface WeeklyCalendar {
    fun updateWeeklyCalendar(events: ArrayList<Event>)
}
