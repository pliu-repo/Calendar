package org.pblmotion.calendar.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.pblmotion.calendar.extensions.config
import org.pblmotion.calendar.extensions.recheckCalDAVCalendars
import org.pblmotion.calendar.extensions.refreshCalDAVCalendars
import org.pblmotion.calendar.extensions.updateWidgets

class CalDAVSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.config.caldavSync) {
            context.refreshCalDAVCalendars(context.config.caldavSyncedCalendarIds, false)
        }

        context.recheckCalDAVCalendars(true) {
            context.updateWidgets()
        }
    }
}
