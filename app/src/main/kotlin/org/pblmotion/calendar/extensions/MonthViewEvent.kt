package org.pblmotion.calendar.extensions

import org.pblmotion.calendar.models.MonthViewEvent

fun MonthViewEvent.shouldStrikeThrough() = isTaskCompleted || isAttendeeInviteDeclined || isEventCanceled
