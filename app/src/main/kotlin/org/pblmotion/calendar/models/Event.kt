package org.pblmotion.calendar.models

import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import androidx.collection.LongSparseArray
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.pblmotion.calendar.extensions.seconds
import org.pblmotion.calendar.helpers.CALDAV
import org.pblmotion.calendar.helpers.DAY
import org.pblmotion.calendar.helpers.FLAG_ALL_DAY
import org.pblmotion.calendar.helpers.FLAG_IS_IN_PAST
import org.pblmotion.calendar.helpers.FLAG_MISSING_YEAR
import org.pblmotion.calendar.helpers.FLAG_TASK_COMPLETED
import org.pblmotion.calendar.helpers.Formatter
import org.pblmotion.calendar.helpers.LOCAL_CALENDAR_ID
import org.pblmotion.calendar.helpers.MONTH
import org.pblmotion.calendar.helpers.REMINDER_NOTIFICATION
import org.pblmotion.calendar.helpers.REMINDER_OFF
import org.pblmotion.calendar.helpers.REPEAT_ORDER_WEEKDAY
import org.pblmotion.calendar.helpers.REPEAT_ORDER_WEEKDAY_USE_LAST
import org.pblmotion.calendar.helpers.REPEAT_SAME_DAY
import org.pblmotion.calendar.helpers.SOURCE_SIMPLE_CALENDAR
import org.pblmotion.calendar.helpers.TYPE_EVENT
import org.pblmotion.calendar.helpers.TYPE_TASK
import org.pblmotion.calendar.helpers.WEEK
import org.pblmotion.calendar.helpers.YEAR
import org.pblmotion.calendar.helpers.getAllTimeZones
import org.pblmotion.calendar.helpers.getNowSeconds
import org.fossify.commons.extensions.addBitIf
import org.joda.time.DateTime
import org.joda.time.DateTimeConstants
import org.joda.time.DateTimeZone
import org.joda.time.Weeks
import java.io.Serializable

@Entity(tableName = "events", indices = [(Index(value = ["id"], unique = true))])
data class Event(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "start_ts") var startTS: Long = 0L,
    @ColumnInfo(name = "end_ts") var endTS: Long = 0L,
    @ColumnInfo(name = "title") var title: String = "",
    @ColumnInfo(name = "location") var location: String = "",
    @ColumnInfo(name = "description") var description: String = "",
    @ColumnInfo(name = "reminder_1_minutes") var reminder1Minutes: Int = REMINDER_OFF,
    @ColumnInfo(name = "reminder_2_minutes") var reminder2Minutes: Int = REMINDER_OFF,
    @ColumnInfo(name = "reminder_3_minutes") var reminder3Minutes: Int = REMINDER_OFF,
    @ColumnInfo(name = "reminder_1_type") var reminder1Type: Int = REMINDER_NOTIFICATION,
    @ColumnInfo(name = "reminder_2_type") var reminder2Type: Int = REMINDER_NOTIFICATION,
    @ColumnInfo(name = "reminder_3_type") var reminder3Type: Int = REMINDER_NOTIFICATION,
    @ColumnInfo(name = "repeat_interval") var repeatInterval: Int = 0,
    @ColumnInfo(name = "repeat_rule") var repeatRule: Int = 0,
    @ColumnInfo(name = "repeat_limit") var repeatLimit: Long = 0L,
    @ColumnInfo(name = "repetition_exceptions") var repetitionExceptions: List<String> = emptyList(),
    @ColumnInfo(name = "attendees") var attendees: List<Attendee> = emptyList(),
    @ColumnInfo(name = "import_id") var importId: String = "",
    @ColumnInfo(name = "time_zone") var timeZone: String = "",
    @ColumnInfo(name = "flags") var flags: Int = 0,
    @ColumnInfo(name = "event_type") var calendarId: Long = LOCAL_CALENDAR_ID,
    @ColumnInfo(name = "parent_id") var parentId: Long = 0,
    @ColumnInfo(name = "last_updated") var lastUpdated: Long = 0L,
    @ColumnInfo(name = "source") var source: String = SOURCE_SIMPLE_CALENDAR,
    @ColumnInfo(name = "availability") var availability: Int = 0,
    @ColumnInfo(name = "access_level") var accessLevel: Int = CalendarContract.Events.ACCESS_DEFAULT,
    @ColumnInfo(name = "color") var color: Int = 0,
    @ColumnInfo(name = "type") var type: Int = TYPE_EVENT,
    @ColumnInfo(name = "status") var status: Int = CalendarContract.Events.STATUS_CONFIRMED,
) : Serializable {

    companion object {
        private const val serialVersionUID = -32456795132345616L
    }

    /**
     * Advances [startTS] and [endTS] by one repetition step, modifying this event in-place.
     *
     * The step size depends on [repeatInterval] and [repeatRule]:
     * - **Daily**: advances by one calendar day.
     * - **Weekly** (every N weeks): advances by one calendar day so the engine can find all
     *   days-of-week that trigger within the week before moving to the next cycle.
     * - **Monthly** (`REPEAT_SAME_DAY`): preserves the same day-of-month, skipping months
     *   that don't have that day (e.g., 31st of month in a 30-day month).
     * - **Monthly / Yearly** (`REPEAT_ORDER_WEEKDAY` / `REPEAT_ORDER_WEEKDAY_USE_LAST`):
     *   lands on the Nth (or last) weekday of the target month/year.
     * - **Yearly** (same day): handles 29 Feb by skipping non-leap years.
     *
     * @param original The unmodified original event used as reference for ordinal calculations.
     */
    fun addIntervalTime(original: Event) {
        val oldStart = Formatter.getDateTimeFromTS(startTS)
        val newStart = when (repeatInterval) {
            DAY -> oldStart.plusDays(1)
            else -> {
                when {
                    repeatInterval % YEAR == 0 -> when (repeatRule) {
                        REPEAT_ORDER_WEEKDAY -> addXthDayInterval(oldStart, original, false)
                        REPEAT_ORDER_WEEKDAY_USE_LAST -> addXthDayInterval(oldStart, original, true)
                        else -> addYearsWithSameDay(oldStart)
                    }

                    repeatInterval % MONTH == 0 -> when (repeatRule) {
                        REPEAT_SAME_DAY -> addMonthsWithSameDay(oldStart, original)
                        REPEAT_ORDER_WEEKDAY -> addXthDayInterval(oldStart, original, false)
                        REPEAT_ORDER_WEEKDAY_USE_LAST -> addXthDayInterval(oldStart, original, true)
                        else -> oldStart.plusMonths(repeatInterval / MONTH).dayOfMonth()
                            .withMaximumValue()
                    }

                    repeatInterval % WEEK == 0 -> {
                        // step through weekly repetition by days too, as events can trigger multiple times a week
                        oldStart.plusDays(1)
                    }

                    else -> oldStart.plusSeconds(repeatInterval)
                }
            }
        }

        val newStartTS = newStart.seconds()
        val newEndTS = newStartTS + (endTS - startTS)
        startTS = newStartTS
        endTS = newEndTS
    }

    // if an event should happen on 29th Feb. with Same Day yearly repetition, show it only on leap years
    private fun addYearsWithSameDay(currStart: DateTime): DateTime {
        var newDateTime = currStart.plusYears(repeatInterval / YEAR)

        // Date may slide within the same month
        if (newDateTime.dayOfMonth != currStart.dayOfMonth) {
            while (newDateTime.dayOfMonth().maximumValue < currStart.dayOfMonth) {
                newDateTime = newDateTime.plusYears(repeatInterval / YEAR)
            }
            newDateTime = newDateTime.withDayOfMonth(currStart.dayOfMonth)
        }
        return newDateTime
    }

    // if an event should happen on 31st with Same Day monthly repetition, dont show it at all at months with 30 or less days
    private fun addMonthsWithSameDay(currStart: DateTime, original: Event): DateTime {
        var newDateTime = currStart.plusMonths(repeatInterval / MONTH)
        if (newDateTime.dayOfMonth == currStart.dayOfMonth) {
            return newDateTime
        }

        while (newDateTime.dayOfMonth().maximumValue < Formatter.getDateTimeFromTS(original.startTS)
                .dayOfMonth().maximumValue
        ) {
            newDateTime = newDateTime.plusMonths(repeatInterval / MONTH)
            newDateTime = try {
                newDateTime.withDayOfMonth(currStart.dayOfMonth)
            } catch (e: Exception) {
                newDateTime
            }
        }
        return newDateTime
    }

    // handle monthly repetitions like Third Monday
    private fun addXthDayInterval(
        currStart: DateTime,
        original: Event,
        forceLastWeekday: Boolean
    ): DateTime {
        val day = currStart.dayOfWeek
        var order = (currStart.dayOfMonth - 1) / 7
        var properMonth =
            currStart.withDayOfMonth(7).plusMonths(repeatInterval / MONTH).withDayOfWeek(day)
        var wantedDay: Int

        // check if it should be for example Fourth Monday, or Last Monday
        if (forceLastWeekday && (order == 3 || order == 4)) {
            val originalDateTime = Formatter.getDateTimeFromTS(original.startTS)
            val isLastWeekday =
                originalDateTime.monthOfYear != originalDateTime.plusDays(7).monthOfYear
            if (isLastWeekday)
                order = -1
        }

        if (order == -1) {
            wantedDay =
                properMonth.dayOfMonth + ((properMonth.dayOfMonth().maximumValue - properMonth.dayOfMonth) / 7) * 7
        } else {
            wantedDay = properMonth.dayOfMonth + (order - (properMonth.dayOfMonth - 1) / 7) * 7
            while (properMonth.dayOfMonth().maximumValue < wantedDay) {
                properMonth = properMonth.withDayOfMonth(7).plusMonths(repeatInterval / MONTH)
                    .withDayOfWeek(day)
                wantedDay = properMonth.dayOfMonth + (order - (properMonth.dayOfMonth - 1) / 7) * 7
            }
        }

        return properMonth.withDayOfMonth(wantedDay)
    }

    fun getIsAllDay() = flags and FLAG_ALL_DAY != 0
    fun hasMissingYear() = flags and FLAG_MISSING_YEAR != 0

    /** Returns `true` if this event is a task (`type == TYPE_TASK`). */
    fun isTask() = type == TYPE_TASK

    /** Returns `true` if this is a task **and** the `FLAG_TASK_COMPLETED` bit is set in [flags]. */
    fun isTaskCompleted() = isTask() && flags and FLAG_TASK_COMPLETED != 0

    /**
     * Returns the configured reminders for this event, excluding disabled ones.
     *
     * Each [Reminder] pairs a number of minutes before the event with a delivery type
     * ([REMINDER_NOTIFICATION] or [REMINDER_EMAIL]). Reminders set to [REMINDER_OFF] are
     * filtered out.
     */
    fun getReminders() = listOf(
        Reminder(reminder1Minutes, reminder1Type),
        Reminder(reminder2Minutes, reminder2Type),
        Reminder(reminder3Minutes, reminder3Type)
    ).filter { it.minutes != REMINDER_OFF }

    /**
     * Returns the event start timestamp, normalised to midnight for all-day events.
     *
     * All-day events are stored with arbitrary-time timestamps; this normalises them to
     * midnight so alarm scheduling and display use consistent values.
     */
    fun getEventStartTS(): Long {
        return if (getIsAllDay()) {
            Formatter.getDateTimeFromTS(startTS).withTime(0, 0, 0, 0).seconds()
        } else {
            startTS
        }
    }

    /**
     * Extracts the CalDAV event ID from [importId].
     *
     * CalDAV import IDs are formatted as `"<uuid>-<calDAVEventId>"`. Returns `0` if the ID
     * cannot be parsed or is not present.
     */
    fun getCalDAVEventId(): Long {
        return try {
            (importId.split("-").lastOrNull() ?: "0").toString().toLong()
        } catch (e: NumberFormatException) {
            0L
        }
    }

    /**
     * Extracts the CalDAV calendar ID from the [source] field.
     *
     * CalDAV events have a source formatted as `"Caldav-<calendarId>"`. Returns `0` for
     * locally-created events.
     */
    fun getCalDAVCalendarId() =
        if (source.startsWith(CALDAV)) (source.split("-").lastOrNull() ?: "0").toString()
            .toInt() else 0

    /**
     * Determines whether this occurrence falls on the correct week cycle for a multi-week
     * repeating event.
     *
     * Events that repeat every N weeks should only fire in weeks that are a multiple of N
     * from the original start week. This method computes how many full ISO weeks have elapsed
     * since the original event's start and checks divisibility.
     *
     * **Note:** The week start is hard-coded to Monday (ISO 8601). This may differ from the
     * user's "Start of week" preference and could be improved in a future revision.
     *
     * @param startTimes A sparse array mapping event ID → original start timestamp, used to
     *   find the canonical week-start reference for this event.
     * @return `true` if this occurrence is in a valid repeat-cycle week.
     */
    fun isOnProperWeek(startTimes: LongSparseArray<Long>): Boolean {
        // Note that the code below hard-codes the start of the week to be Monday. This affects events that repeat on
        // multiple days of the week. Ideally this should be configurable; but doing it properly will require some work.
        // For example, Google Calendar uses the value of the "Start of the week" setting at the time the event is
        // created/edited (critically, changing the setting does not affect existing events). That seems like the best
        // approach. Implementing this would require adding a (hidden) start-of-week field to events (corresponding to
        // iCal's WKST rule).
        if (repeatInterval == WEEK) {
            return true // optimization for events that repeat every week
        }
        val initialDate = Formatter.getDateFromTS(startTimes[id!!]!!)
        val daysSinceWeekStart = Math.floorMod(initialDate.dayOfWeek - DateTimeConstants.MONDAY, 7)
        val initialWeekStart = initialDate.minusDays(daysSinceWeekStart)
        val currentDate = Formatter.getDateFromTS(startTS)
        val weeks = Weeks.weeksBetween(initialWeekStart, currentDate).weeks
        return weeks % (repeatInterval / WEEK) == 0
    }

    /**
     * Updates [isPastEvent] based on whether [endTS] is in the past.
     *
     * For all-day events that started before now, the end-of-day timestamp is used rather than
     * the raw [endTS], so all-day events appear as past only after their day has fully elapsed.
     */
    fun updateIsPastEvent() {
        val endTSToCheck = if (startTS < getNowSeconds() && getIsAllDay()) {
            Formatter.getDayEndTS(Formatter.getDayCodeFromTS(endTS))
        } else {
            endTS
        }
        isPastEvent = endTSToCheck < getNowSeconds()
    }

    /**
     * Marks [dayCode] as a skipped occurrence for this repeating event.
     *
     * When a user deletes a single occurrence of a repeating event, the day code of that
     * occurrence is added to [repetitionExceptions]. Future calls to the event-expansion
     * engine will skip that date. Duplicates are automatically removed.
     *
     * @param dayCode The day code (`YYYYMMdd`) of the occurrence to exclude.
     */
    fun addRepetitionException(dayCode: String) {
        var newRepetitionExceptions = repetitionExceptions.toMutableList()
        newRepetitionExceptions.add(dayCode)
        newRepetitionExceptions =
            newRepetitionExceptions.distinct().toMutableList() as ArrayList<String>
        repetitionExceptions = newRepetitionExceptions
    }

    var isPastEvent: Boolean
        get() = flags and FLAG_IS_IN_PAST != 0
        set(isPastEvent) {
            flags = flags.addBitIf(isPastEvent, FLAG_IS_IN_PAST)
        }

    fun getTimeZoneString(): String {
        return if (timeZone.isNotEmpty() && getAllTimeZones().map { it.zoneName }
                .contains(timeZone)) {
            timeZone
        } else {
            DateTimeZone.getDefault().id
        }
    }

    fun isAttendeeInviteDeclined() = attendees.any {
        it.isMe && it.status == Attendees.ATTENDEE_STATUS_DECLINED
    }

    fun isEventCanceled(): Boolean {
        return status == CalendarContract.Events.STATUS_CANCELED
    }
}
