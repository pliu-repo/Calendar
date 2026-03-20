package org.pblmotion.calendar

import org.pblmotion.calendar.extensions.hasDummyAlarm
import org.pblmotion.calendar.jobs.AppStartupWorker
import org.fossify.commons.FossifyApp

class App : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        if (!hasDummyAlarm()) {
            AppStartupWorker.start(this)
        }
    }
}
