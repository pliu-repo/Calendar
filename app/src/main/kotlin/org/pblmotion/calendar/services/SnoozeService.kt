package org.pblmotion.calendar.services

import android.app.IntentService
import android.content.Intent
import org.pblmotion.calendar.extensions.config
import org.pblmotion.calendar.extensions.eventsDB
import org.pblmotion.calendar.extensions.rescheduleReminder
import org.pblmotion.calendar.helpers.EVENT_ID

class SnoozeService : IntentService("Snooze") {
    override fun onHandleIntent(intent: Intent?) {
        if (intent != null) {
            val eventId = intent.getLongExtra(EVENT_ID, 0L)
            val event = eventsDB.getEventOrTaskWithId(eventId)
            rescheduleReminder(event, config.snoozeTime)
        }
    }
}
