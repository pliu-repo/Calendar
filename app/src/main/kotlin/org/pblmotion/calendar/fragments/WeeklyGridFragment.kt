package org.pblmotion.calendar.fragments

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.pblmotion.calendar.R
import org.pblmotion.calendar.databinding.FragmentWeeklyGridBinding
import org.pblmotion.calendar.databinding.WeeklyGridDayColumnBinding
import org.pblmotion.calendar.databinding.WeeklyGridEventItemBinding
import org.pblmotion.calendar.extensions.checkViewStrikeThrough
import org.pblmotion.calendar.extensions.config
import org.pblmotion.calendar.extensions.eventsDB
import org.pblmotion.calendar.extensions.eventsHelper
import org.pblmotion.calendar.extensions.getFirstDayOfWeekDt
import org.pblmotion.calendar.helpers.DAY_CODE
import org.pblmotion.calendar.helpers.EVENT_ID
import org.pblmotion.calendar.helpers.EVENT_OCCURRENCE_TS
import org.pblmotion.calendar.helpers.Formatter
import org.pblmotion.calendar.helpers.IS_TASK_COMPLETED
import org.pblmotion.calendar.helpers.TaskifyHelper
import org.pblmotion.calendar.helpers.WEEKLY_GRID_VIEW
import org.pblmotion.calendar.helpers.getActivityToOpen
import org.pblmotion.calendar.models.Event
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.fossify.commons.helpers.ensureBackgroundThread
import org.joda.time.DateTime
import org.joda.time.DateTimeConstants

/**
 * A calendar view fragment that displays a full week as a compact 7-day grid.
 *
 * Unlike the weekly timeline ([WeekFragmentsHolder]), this fragment renders all seven days
 * in a two-column layout (pairs of days), making it suitable for a quick per-day overview
 * without a time axis. It is not backed by a `ViewPager`; instead, a single instance loads
 * all seven days of the selected week in one shot.
 *
 * ## Taskify Events Mode
 * When [org.pblmotion.calendar.helpers.Config.taskifyEventsMode] is `true`, each event item
 * shows:
 * - An **important icon** on the left (if flagged with `[!]`).
 * - A **checkbox** on the right; tapping it toggles the `[C]` completion suffix and updates
 *   both the database and the item's visual state in-place.
 * - A **red strike-through line** and dimmed text colour for completed events.
 *
 * ## Navigation argument
 * Provide the week start date as a day code (`YYYYMMdd`) via the [DAY_CODE] Bundle key.
 * Use [newInstance] to construct the fragment with the correct argument.
 */
class WeeklyGridFragment : MyFragmentHolder() {

    private lateinit var binding: FragmentWeeklyGridBinding
    private var weekStartCode = ""

    override val viewType = WEEKLY_GRID_VIEW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        weekStartCode = arguments?.getString(DAY_CODE) ?: Formatter.getTodayCode()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentWeeklyGridBinding.inflate(inflater, container, false)
        binding.root.background = ColorDrawable(requireContext().getProperBackgroundColor())
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadWeekEvents()
    }

    /**
     * Queries all events for the current week and rebuilds the grid.
     *
     * The query runs on a background thread via [EventsHelper.getEvents]; the result is
     * delivered on the main thread and forwarded to [buildGrid].
     */
    private fun loadWeekEvents() {
        val weekStart = Formatter.getDateTimeFromCode(weekStartCode)
        val weekStartTS = weekStart.withTimeAtStartOfDay().millis / 1000
        val weekEndTS = weekStart.plusDays(7).withTimeAtStartOfDay().millis / 1000

        requireContext().eventsHelper.getEvents(weekStartTS, weekEndTS) { events ->
            val grouped = HashMap<String, ArrayList<Event>>()
            for (i in 0 until 7) {
                val dayCode = Formatter.getDayCodeFromDateTime(weekStart.plusDays(i))
                grouped[dayCode] = ArrayList()
            }
            events.forEach { event ->
                val dayCode = Formatter.getDayCodeFromTS(event.startTS)
                grouped.getOrPut(dayCode) { ArrayList() }.add(event)
            }

            activity?.runOnUiThread {
                if (isAdded) {
                    buildGrid(weekStart, grouped)
                }
            }
        }
    }

    /**
     * Inflates and populates each day column in the grid.
     *
     * Days are rendered in Sunday–Saturday order. For each day, a [WeeklyGridDayColumnBinding]
     * is inflated into the matching [FrameLayout] cell. Events are sorted by start time and
     * each receives a [WeeklyGridEventItemBinding] configured via [setupEventItem].
     *
     * @param weekStart The Monday-equivalent start of the week for which data was loaded.
     * @param grouped A map from day code to the list of events for that day.
     */
    private fun buildGrid(weekStart: DateTime, grouped: HashMap<String, ArrayList<Event>>) {
        clearGridCells()
        val todayCode = Formatter.getTodayCode()
        val primaryColor = requireContext().getProperPrimaryColor()
        val textColor = requireContext().getProperTextColor()
        val taskifyEventsMode = requireContext().config.taskifyEventsMode

        val daysByWeekday = (0 until 7)
            .map { weekStart.plusDays(it) }
            .associateBy { it.dayOfWeek }

        val orderedWeekdays = listOf(
            DateTimeConstants.SUNDAY,
            DateTimeConstants.MONDAY,
            DateTimeConstants.TUESDAY,
            DateTimeConstants.WEDNESDAY,
            DateTimeConstants.THURSDAY,
            DateTimeConstants.FRIDAY,
            DateTimeConstants.SATURDAY
        )

        orderedWeekdays.forEach { weekday ->
            val day = daysByWeekday[weekday] ?: return@forEach
            val dayCode = Formatter.getDayCodeFromDateTime(day)
            val events = grouped[dayCode].orEmpty()
            val isToday = dayCode == todayCode
            val container = getContainerForWeekday(weekday)
            val columnBinding = WeeklyGridDayColumnBinding.inflate(layoutInflater, container, false)

            columnBinding.weeklyGridDayLabel.apply {
                text = day.toString("EEE, M/d/yy")
                val weekendColor = when (weekday) {
                    DateTimeConstants.SUNDAY -> resources.getColor(R.color.taskify_strikethrough, null)
                    DateTimeConstants.SATURDAY -> primaryColor
                    else -> textColor
                }
                setTextColor(if (isToday) primaryColor else weekendColor)
            }

            events.sortedBy { it.startTS }.forEach { event ->
                val eventBinding = WeeklyGridEventItemBinding.inflate(layoutInflater, columnBinding.weeklyGridDayEvents, false)
                setupEventItem(eventBinding, event, taskifyEventsMode, textColor)
                columnBinding.weeklyGridDayEvents.addView(eventBinding.root)
            }

            container.addView(columnBinding.root)
        }
    }

    /** Removes all inflated views from every day cell so [buildGrid] can repopulate them. */
    private fun clearGridCells() {
        binding.weeklyGridCellSunday.removeAllViews()
        binding.weeklyGridCellMonday.removeAllViews()
        binding.weeklyGridCellTuesday.removeAllViews()
        binding.weeklyGridCellWednesday.removeAllViews()
        binding.weeklyGridCellThursday.removeAllViews()
        binding.weeklyGridCellFriday.removeAllViews()
        binding.weeklyGridCellSaturday.removeAllViews()
    }

    /**
     * Returns the [FrameLayout] cell that corresponds to [weekday].
     *
     * @param weekday A [DateTimeConstants] weekday constant (e.g., [DateTimeConstants.MONDAY]).
     * @return The matching day-column container in [binding].
     */
    private fun getContainerForWeekday(weekday: Int): FrameLayout = when (weekday) {
        DateTimeConstants.SUNDAY -> binding.weeklyGridCellSunday
        DateTimeConstants.MONDAY -> binding.weeklyGridCellMonday
        DateTimeConstants.TUESDAY -> binding.weeklyGridCellTuesday
        DateTimeConstants.WEDNESDAY -> binding.weeklyGridCellWednesday
        DateTimeConstants.THURSDAY -> binding.weeklyGridCellThursday
        DateTimeConstants.FRIDAY -> binding.weeklyGridCellFriday
        else -> binding.weeklyGridCellSaturday
    }

    /**
     * Binds a single event to its grid item view.
     *
     * Handles both regular events and Taskify Events Mode rendering:
     * - In Taskify mode, parses the title suffix and shows the appropriate checkbox, important
     *   icon, and completed-line overlay.
     * - In normal mode, hides all Taskify-specific elements.
     *
     * @param itemBinding The inflated item view binding.
     * @param event The event to display.
     * @param taskifyEventsMode Whether Taskify Events Mode is currently active.
     * @param textColor The default text colour from the current theme.
     */
    private fun setupEventItem(
        itemBinding: WeeklyGridEventItemBinding,
        event: Event,
        taskifyEventsMode: Boolean,
        textColor: Int
    ) {
        val taskifyMeta = if (taskifyEventsMode && !event.isTask()) {
            TaskifyHelper.parseTitle(event.title, taskifyModeEnabled = true)
        } else {
            null
        }

        val displayTitle = taskifyMeta?.cleanTitle ?: event.title
        val isCompleted = taskifyMeta?.isCompleted ?: event.isTaskCompleted()
        val isImportant = taskifyMeta?.isImportant ?: false
        val eventTextColor = if (isCompleted) textColor.adjustAlpha(MEDIUM_ALPHA) else textColor
        val eventTitle = if (event.getIsAllDay()) {
            displayTitle
        } else {
            "${Formatter.getTimeFromTS(requireContext(), event.startTS)} - $displayTitle"
        }

        // Keep weekly grid close to the classic compact look (no color bar).
        itemBinding.weeklyGridEventColorBar.beGone()
        itemBinding.weeklyGridEventTitle.text = eventTitle
        itemBinding.weeklyGridEventTitle.setTextColor(eventTextColor)
        itemBinding.weeklyGridEventTitle.checkViewStrikeThrough(isCompleted)
        itemBinding.weeklyGridEventTitle.setTypeface(null, if (isImportant) Typeface.BOLD else Typeface.NORMAL)

        itemBinding.weeklyGridEventImportantImage.beVisibleIf(isImportant)
        if (isImportant) {
            itemBinding.weeklyGridEventImportantImage.applyColorFilter(resources.getColor(R.color.taskify_strikethrough, null))
        }

        itemBinding.weeklyGridEventCompletedLine.beVisibleIf(isCompleted)

        if (taskifyEventsMode && !event.isTask()) {
            val checkboxRes = if (isCompleted) R.drawable.ic_checkbox_checked_vector else R.drawable.ic_checkbox_unchecked_vector
            itemBinding.weeklyGridEventCheckbox.setImageResource(checkboxRes)
            itemBinding.weeklyGridEventCheckbox.beVisible()
            itemBinding.weeklyGridEventCheckbox.setOnClickListener {
                toggleTaskifyCompletion(event, itemBinding, textColor)
            }
        } else {
            itemBinding.weeklyGridEventCheckbox.beGone()
        }

        itemBinding.root.setOnClickListener {
            val intent = Intent(requireContext(), getActivityToOpen(event.isTask()))
            intent.putExtra(EVENT_ID, event.id!!)
            intent.putExtra(EVENT_OCCURRENCE_TS, event.startTS)
            intent.putExtra(IS_TASK_COMPLETED, event.isTaskCompleted())
            startActivity(intent)
        }
    }

    /**
     * Toggles the Taskify completion state of [event] and refreshes the item view in-place.
     *
     * The toggle runs on a background thread:
     * 1. Fetches the freshest version of the event from the database.
     * 2. Flips the `[C]` completion suffix using [TaskifyHelper.encodeTitle].
     * 3. Persists the new title via [EventsHelper.updateEvent].
     * 4. Updates the UI (checkbox drawable, strike-through, text colour, completed-line) on
     *    the main thread without requiring a full grid reload.
     *
     * @param event The in-memory event object (its `title` is updated after the DB write).
     * @param itemBinding The view binding for the event's row, used for in-place UI updates.
     * @param textColor The default text colour, used to restore or dim the title text.
     */
    private fun toggleTaskifyCompletion(event: Event, itemBinding: WeeklyGridEventItemBinding, textColor: Int) {
        ensureBackgroundThread {
            val ctx = requireContext()
            val freshEvent = ctx.eventsDB.getEventWithId(event.id ?: return@ensureBackgroundThread) ?: return@ensureBackgroundThread
            val taskMeta = TaskifyHelper.parseTitle(freshEvent.title, taskifyModeEnabled = true)
            val newCompleted = !taskMeta.isCompleted
            val newTitle = TaskifyHelper.encodeTitle(taskMeta.cleanTitle, taskMeta.isImportant, newCompleted)
            freshEvent.title = newTitle
            ctx.eventsHelper.updateEvent(freshEvent, updateAtCalDAV = true, showToasts = false)
            event.title = newTitle
            activity?.runOnUiThread {
                if (isAdded) {
                    val newTextColor = if (newCompleted) textColor.adjustAlpha(MEDIUM_ALPHA) else textColor
                    itemBinding.weeklyGridEventTitle.setTextColor(newTextColor)
                    itemBinding.weeklyGridEventTitle.checkViewStrikeThrough(newCompleted)
                    itemBinding.weeklyGridEventCompletedLine.beVisibleIf(newCompleted)
                    val checkboxRes = if (newCompleted) R.drawable.ic_checkbox_checked_vector else R.drawable.ic_checkbox_unchecked_vector
                    itemBinding.weeklyGridEventCheckbox.setImageResource(checkboxRes)
                }
            }
        }
    }


    override fun goToToday() {
        weekStartCode = Formatter.getDayCodeFromDateTime(
            requireContext().getFirstDayOfWeekDt(DateTime.now())
        )
        loadWeekEvents()
    }

    override fun showGoToDateDialog() {}

    override fun refreshEvents() {
        loadWeekEvents()
    }

    override fun shouldGoToTodayBeVisible(): Boolean {
        val todayWeekStart = Formatter.getDayCodeFromDateTime(
            requireContext().getFirstDayOfWeekDt(DateTime.now())
        )
        return weekStartCode != todayWeekStart
    }

    override fun getNewEventDayCode(): String = Formatter.getTodayCode()

    override fun printView() {}

    override fun getCurrentDate(): DateTime? = Formatter.getDateTimeFromCode(weekStartCode)

    companion object {
        /**
         * Creates a new [WeeklyGridFragment] for the week that contains [weekStartCode].
         *
         * @param weekStartCode A day code (`YYYYMMdd`) for any day within the desired week.
         *   Typically the Monday or Sunday of the week, as determined by
         *   [org.pblmotion.calendar.extensions.getFirstDayOfWeekDt].
         */
        fun newInstance(weekStartCode: String): WeeklyGridFragment {
            val fragment = WeeklyGridFragment()
            fragment.arguments = Bundle().apply {
                putString(DAY_CODE, weekStartCode)
            }
            return fragment
        }
    }
}
