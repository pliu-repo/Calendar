package org.fossify.calendar.extensions

import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.helpers.TWELVE_HOURS
import org.fossify.calendar.models.Event
import org.fossify.calendar.models.TaskMeta
import org.joda.time.DateTimeZone

// Matches task title prefixes: optional "! " for important, then "[]" or "[c]", then the actual title
private val TASK_PREFIX_REGEX = Regex("""^(! )?\[(c?)\] ?(.*)""", RegexOption.DOT_MATCHES_ALL)

/** Parse a raw event title and extract any embedded task metadata. */
fun String.parseTaskMeta(): TaskMeta {
    val match = TASK_PREFIX_REGEX.matchEntire(this)
    return if (match != null) {
        TaskMeta(
            isTask = true,
            isImportant = match.groupValues[1].isNotEmpty(),
            isCompleted = match.groupValues[2] == "c",
            cleanTitle = match.groupValues[3],
        )
    } else {
        TaskMeta(isTask = false, isImportant = false, isCompleted = false, cleanTitle = this)
    }
}

/**
 * Derive [TaskMeta] for an [Event], merging native task flags with any title-prefix encoding.
 *
 * - Local tasks (`isTask() == true`) use [Event.isTaskCompleted] for the completed flag and the
 *   title prefix only for the "important" and "clean title" information.
 * - External events that encode task state purely via title prefixes (`[]`/`[c]`) are also
 *   surfaced as tasks even when the `type` field is not [org.fossify.calendar.helpers.TYPE_TASK].
 */
val Event.taskMeta: TaskMeta
    get() {
        val titleMeta = title.parseTaskMeta()
        return when {
            isTask() -> TaskMeta(
                isTask = true,
                isImportant = titleMeta.isImportant,
                isCompleted = isTaskCompleted(),
                cleanTitle = if (titleMeta.isTask) titleMeta.cleanTitle else title,
            )
            titleMeta.isTask -> titleMeta
            else -> TaskMeta(isTask = false, isImportant = false, isCompleted = false, cleanTitle = title)
        }
    }

// shifts all-day events to local timezone such that the event starts and ends on the same time as in UTC
fun Event.toLocalAllDayEvent() {
    require(this.getIsAllDay()) { "Must be an all day event!" }

    timeZone = DateTimeZone.getDefault().id
    startTS = Formatter.getShiftedLocalTS(startTS)
    endTS = Formatter.getShiftedLocalTS(endTS)
    if (endTS > startTS) {
        endTS -= TWELVE_HOURS
    }
}

// shifts all-day events to UTC such that the event starts on the same time in UTC too
fun Event.toUtcAllDayEvent() {
    require(getIsAllDay()) { "Must be an all day event!" }

    if (endTS >= startTS) {
        endTS += TWELVE_HOURS
    }

    timeZone = DateTimeZone.UTC.id
    startTS = Formatter.getShiftedUtcTS(startTS)
    endTS = Formatter.getShiftedUtcTS(endTS)
}

// this is to make sure the repetition ends on the date set when creating the original event
fun Event.maybeAdjustRepeatLimitCount(original: Event, occurrenceTS: Long) {
    val hasFixedRepeatCount = original.repeatLimit < 0 && repeatLimit < 0
    val repeatLimitUnchanged = original.repeatLimit == repeatLimit
    if (hasFixedRepeatCount && repeatLimitUnchanged) {
        val occurrencesSinceStart = (occurrenceTS - original.startTS) / original.repeatInterval
        val newRepeatLimit = repeatLimit + occurrencesSinceStart
        this.repeatLimit = newRepeatLimit
    }
}

fun Event.shouldStrikeThrough() = taskMeta.isCompleted || isAttendeeInviteDeclined() || isEventCanceled()
