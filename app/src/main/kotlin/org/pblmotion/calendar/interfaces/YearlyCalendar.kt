package org.pblmotion.calendar.interfaces

import android.util.SparseArray
import org.pblmotion.calendar.models.DayYearly

interface YearlyCalendar {
    fun updateYearlyCalendar(events: SparseArray<ArrayList<DayYearly>>, hashCode: Int)
}
