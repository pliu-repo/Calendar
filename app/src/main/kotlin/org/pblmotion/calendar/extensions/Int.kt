package org.pblmotion.calendar.extensions

import org.pblmotion.calendar.helpers.MONTH
import org.pblmotion.calendar.helpers.WEEK
import org.pblmotion.calendar.helpers.YEAR

fun Int.isXWeeklyRepetition() = this != 0 && this % WEEK == 0

fun Int.isXMonthlyRepetition() = this != 0 && this % MONTH == 0

fun Int.isXYearlyRepetition() = this != 0 && this % YEAR == 0
