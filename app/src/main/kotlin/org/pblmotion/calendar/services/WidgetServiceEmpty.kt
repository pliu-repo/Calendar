package org.pblmotion.calendar.services

import android.content.Intent
import android.widget.RemoteViewsService
import org.pblmotion.calendar.adapters.EventListWidgetAdapterEmpty

class WidgetServiceEmpty : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent) = EventListWidgetAdapterEmpty(applicationContext)
}
