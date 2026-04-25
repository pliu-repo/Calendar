package org.pblmotion.calendar.extensions

import org.pblmotion.calendar.helpers.Formatter
import org.pblmotion.calendar.helpers.TWELVE_HOURS
import org.pblmotion.calendar.models.Event
import org.pblmotion.calendar.models.TaskMeta
import org.joda.time.DateTimeZone

// Matches task title prefixes: optional "! " for important, then "[]" or "[c]", then the actual title
private val TASK_PREFIX_REGEX = Regex("""^(! )?\[(c?)\] ?(.*)""", RegexOption.DOT_MATCHES_ALL)

/**
 * Parses this raw event title string and extracts any embedded legacy task metadata.
 *
 * The legacy prefix format used by Simple/Fossify Tasks is:
 * - `"[] My task"` → incomplete task
 * - `"[c] My task"` → completed task
 * - `"! [] My task"` → important, incomplete task
 * - `"! [c] My task"` → important, completed task
 *
 * @return A [TaskMeta] with the decoded flags, or a "not a task" meta if no prefix is found.
 */
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
 * Derives [TaskMeta] for this [Event], merging native Room task flags with any title-prefix encoding.
 *
 * Resolution rules (in priority order):
 * 1. **Local tasks** (`isTask() == true`): use [Event.isTaskCompleted] for the `isCompleted` flag;
 *    derive `isImportant` and `cleanTitle` from the title prefix if present.
 * 2. **Prefix-encoded events** (title begins with `[]`/`[c]`): treated as tasks even when
 *    the database `type` field is not `TYPE_TASK` (e.g., CalDAV events using the old encoding).
 * 3. **Plain events**: returns a "not a task" meta where `isTask = false` and `cleanTitle = title`.
 *
 * @see parseTaskMeta
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

/**
 * Shifts this all-day event's timestamps from UTC to local timezone.
 *
 * All-day events are stored with midnight UTC timestamps. Call this before displaying or
 * editing such an event locally so that the rendered date matches the user's locale.
 *
 * @throws IllegalArgumentException if this event is not an all-day event.
 * @see toUtcAllDayEvent
 */
fun Event.toLocalAllDayEvent() {
    require(this.getIsAllDay()) { "Must be an all day event!" }

    timeZone = DateTimeZone.getDefault().id
    startTS = Formatter.getShiftedLocalTS(startTS)
    endTS = Formatter.getShiftedLocalTS(endTS)
    if (endTS > startTS) {
        endTS -= TWELVE_HOURS
    }
}

/**
 * Shifts this all-day event's timestamps from local timezone back to UTC.
 *
 * Call this before persisting an all-day event so that the stored timestamps are
 * midnight UTC, compatible with the CalDAV / ICS standard.
 *
 * @throws IllegalArgumentException if this event is not an all-day event.
 * @see toLocalAllDayEvent
 */
fun Event.toUtcAllDayEvent() {
    require(getIsAllDay()) { "Must be an all day event!" }

    if (endTS >= startTS) {
        endTS += TWELVE_HOURS
    }

    timeZone = DateTimeZone.UTC.id
    startTS = Formatter.getShiftedUtcTS(startTS)
    endTS = Formatter.getShiftedUtcTS(endTS)
}

/**
 * Adjusts the repeat limit count for an occurrence of a repeating event, so that edits to
 * future occurrences ("edit this and following") preserve the originally intended end date.
 *
 * When the user edits a future occurrence, the occurrence's `repeatLimit` is inherited from
 * the original event. This function offsets it by the number of occurrences that have already
 * passed, keeping the repeat end date consistent.
 *
 * @param original The unmodified original (parent) event.
 * @param occurrenceTS The timestamp of the first occurrence that will use the new settings.
 */
fun Event.maybeAdjustRepeatLimitCount(original: Event, occurrenceTS: Long) {
    val hasFixedRepeatCount = original.repeatLimit < 0 && repeatLimit < 0
    val repeatLimitUnchanged = original.repeatLimit == repeatLimit
    if (hasFixedRepeatCount && repeatLimitUnchanged) {
        val occurrencesSinceStart = (occurrenceTS - original.startTS) / original.repeatInterval
        val newRepeatLimit = repeatLimit + occurrencesSinceStart
        this.repeatLimit = newRepeatLimit
    }
}

/**
 * Returns `true` when the event title should be rendered with a strike-through style.
 *
 * This is `true` for:
 * - Completed tasks (`taskMeta.isCompleted`)
 * - Events where the attendee (the user) has declined the invitation
 * - Cancelled events (CalDAV `STATUS:CANCELLED`)
 */
fun Event.shouldStrikeThrough() = taskMeta.isCompleted || isAttendeeInviteDeclined() || isEventCanceled()
