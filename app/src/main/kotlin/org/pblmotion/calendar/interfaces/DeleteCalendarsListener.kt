package org.pblmotion.calendar.interfaces

import org.pblmotion.calendar.models.CalendarEntity

interface DeleteCalendarsListener {
    fun deleteCalendars(calendars: ArrayList<CalendarEntity>, deleteEvents: Boolean): Boolean
}
