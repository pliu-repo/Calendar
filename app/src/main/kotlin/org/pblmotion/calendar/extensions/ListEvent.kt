package org.pblmotion.calendar.extensions

import org.pblmotion.calendar.models.ListEvent

fun ListEvent.shouldStrikeThrough() = isTaskCompleted || isAttendeeInviteDeclined || isEventCanceled
