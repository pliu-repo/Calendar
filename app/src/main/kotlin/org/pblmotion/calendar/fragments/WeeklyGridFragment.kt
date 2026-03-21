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
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.joda.time.DateTime
import org.joda.time.DateTimeConstants

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

    private fun clearGridCells() {
        binding.weeklyGridCellSunday.removeAllViews()
        binding.weeklyGridCellMonday.removeAllViews()
        binding.weeklyGridCellTuesday.removeAllViews()
        binding.weeklyGridCellWednesday.removeAllViews()
        binding.weeklyGridCellThursday.removeAllViews()
        binding.weeklyGridCellFriday.removeAllViews()
        binding.weeklyGridCellSaturday.removeAllViews()
    }

    private fun getContainerForWeekday(weekday: Int): FrameLayout = when (weekday) {
        DateTimeConstants.SUNDAY -> binding.weeklyGridCellSunday
        DateTimeConstants.MONDAY -> binding.weeklyGridCellMonday
        DateTimeConstants.TUESDAY -> binding.weeklyGridCellTuesday
        DateTimeConstants.WEDNESDAY -> binding.weeklyGridCellWednesday
        DateTimeConstants.THURSDAY -> binding.weeklyGridCellThursday
        DateTimeConstants.FRIDAY -> binding.weeklyGridCellFriday
        else -> binding.weeklyGridCellSaturday
    }

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

        // Keep weekly grid close to the classic compact look (no color bar/checkbox).
        itemBinding.weeklyGridEventColorBar.beGone()
        itemBinding.weeklyGridEventCheckbox.beGone()
        itemBinding.weeklyGridEventTitle.text = eventTitle
        itemBinding.weeklyGridEventTitle.setTextColor(eventTextColor)
        itemBinding.weeklyGridEventTitle.checkViewStrikeThrough(isCompleted)
        itemBinding.weeklyGridEventTitle.setTypeface(null, if (isImportant) Typeface.BOLD else Typeface.NORMAL)

        itemBinding.weeklyGridEventImportantImage.beVisibleIf(isImportant)
        if (isImportant) {
            itemBinding.weeklyGridEventImportantImage.applyColorFilter(resources.getColor(R.color.taskify_strikethrough, null))
        }

        itemBinding.weeklyGridEventCompletedLine.beVisibleIf(isCompleted)

        itemBinding.root.setOnClickListener {
            val intent = Intent(requireContext(), getActivityToOpen(event.isTask()))
            intent.putExtra(EVENT_ID, event.id!!)
            intent.putExtra(EVENT_OCCURRENCE_TS, event.startTS)
            intent.putExtra(IS_TASK_COMPLETED, event.isTaskCompleted())
            startActivity(intent)
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
        fun newInstance(weekStartCode: String): WeeklyGridFragment {
            val fragment = WeeklyGridFragment()
            fragment.arguments = Bundle().apply {
                putString(DAY_CODE, weekStartCode)
            }
            return fragment
        }
    }
}
