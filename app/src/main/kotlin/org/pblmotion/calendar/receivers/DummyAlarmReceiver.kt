package org.pblmotion.calendar.Receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.pblmotion.calendar.extensions.scheduleDummyAlarm

class DummyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.scheduleDummyAlarm()
    }
}
