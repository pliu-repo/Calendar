package org.pblmotion.calendar.fragments

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.pblmotion.calendar.R
import org.pblmotion.calendar.databinding.FragmentWeeklyGridBinding
import org.pblmotion.calendar.databinding.WeeklyGridEventItemBinding
import org.pblmotion.calendar.extensions.config
import org.pblmotion.calendar.extensions.eventsDB
import org.pblmotion.calendar.extensions.eventsHelper
import org.pblmotion.calendar.extensions.getFirstDayOfWeekDt
import org.pblmotion.calendar.extensions.seconds
import org.pblmotion.calendar.helpers.DAY
import org.pblmotion.calendar.helpers.EVENT_ID
import org.pblmotion.calendar.helpers.EVENT_OCCURRENCE_TS
import org.pblmotion.calendar.helpers.Formatter
import org.pblmotion.calendar.helpers.IS_TASK_COMPLETED
import org.pblmotion.calendar.helpers.WEEKLY_GRID_VIEW
import org.pblmotion.calendar.helpers.WEEK_START_DATE_TIME
import org.pblmotion.calendar.helpers.TaskifyHelper
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
import org.fossify.commons.helpers.WEEK_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.joda.time.DateTime

class WeeklyGridFragment : MyFragmentHolder() {

    private lateinit var binding: FragmentWeeklyGridBinding
    private var currentWeekTS = 0L
    private var thisWeekTS = 0L

    override val viewType = WEEKLY_GRID_VIEW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dateTimeString = arguments?.getString(WEEK_START_DATE_TIME)
        val dt = if (dateTimeString != null) {
            try { DateTime.parse(dateTimeString) } catch (e: Exception) {
                Log.e("WeeklyGridFragment", "Invalid week start date: $dateTimeString", e)
                DateTime()
            }
        } else {
            DateTime()
        }
        currentWeekTS = requireContext().getFirstDayOfWeekDt(dt).seconds()
        thisWeekTS = requireContext().getFirstDayOfWeekDt(DateTime()).seconds()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentWeeklyGridBinding.inflate(inflater, container, false)
        binding.root.background = ColorDrawable(requireContext().getProperBackgroundColor())
        setupNavigation()
        loadWeekEvents()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) loadWeekEvents()
    }

    private fun setupNavigation() {
        val textColor = requireContext().getProperTextColor()
        binding.weeklyGridTitle.setTextColor(textColor)
        binding.weeklyGridPrevWeek.applyColorFilter(textColor)
        binding.weeklyGridNextWeek.applyColorFilter(textColor)
        binding.weeklyGridPrevWeek.setOnClickListener { currentWeekTS -= WEEK_SECONDS; loadWeekEvents() }
        binding.weeklyGridNextWeek.setOnClickListener { currentWeekTS += WEEK_SECONDS; loadWeekEvents() }
    }

    private fun loadWeekEvents() {
        updateTitle()
        val weekStart = currentWeekTS
        val weekEnd = weekStart + WEEK_SECONDS - 1
        requireContext().eventsHelper.getEvents(weekStart, weekEnd) { events ->
            activity?.runOnUiThread { if (isAdded) buildGrid(weekStart, events) }
        }
    }

    private fun updateTitle() {
        val startDt = Formatter.getDateTimeFromTS(currentWeekTS)
        val endDt = startDt.plusDays(6)
        val startMonth = Formatter.getShortMonthName(requireContext(), startDt.monthOfYear)
        val endMonth = Formatter.getShortMonthName(requireContext(), endDt.monthOfYear)
        binding.weeklyGridTitle.text = if (startDt.monthOfYear == endDt.monthOfYear) {
            "$startMonth ${startDt.dayOfMonth}–${endDt.dayOfMonth}, ${startDt.year}"
        } else {
            "$startMonth ${startDt.dayOfMonth} – $endMonth ${endDt.dayOfMonth}, ${endDt.year}"
        }
    }

    private fun buildGrid(weekStartTS: Long, events: ArrayList<Event>) {
        val ctx = requireContext()
        val textColor = ctx.getProperTextColor()
        val primaryColor = ctx.getProperPrimaryColor()
        val dimPastEvents = ctx.config.dimPastEvents
        val dimCompletedTasks = ctx.config.dimCompletedTasks
        val taskifyEventsMode = ctx.config.taskifyEventsMode
        val inflater = layoutInflater
        val dayNamesShort = ctx.resources.getStringArray(org.fossify.commons.R.array.week_days_short)

        // Group events by day column (0..6)
        val dayEvents = Array<MutableList<Event>>(7) { mutableListOf() }
        events.forEach { event ->
            for (dayIdx in 0..6) {
                val dayTS = weekStartTS + dayIdx * DAY
                if (event.startTS <= dayTS + DAY - 1 && event.endTS >= dayTS) {
                    dayEvents[dayIdx].add(event)
                }
            }
        }

        val tinyMargin = ctx.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.tiny_margin)
        val smallMargin = ctx.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
        val headerTextSizePx = ctx.resources.getDimensionPixelSize(R.dimen.day_monthly_text_size)

        binding.weeklyGridDayHeaders.removeAllViews()
        binding.weeklyGridColumns.removeAllViews()

        for (dayIdx in 0..6) {
            val dayDt = Formatter.getDateTimeFromTS(weekStartTS).plusDays(dayIdx)
            val dayOfWeekIdx = (dayDt.dayOfWeek - 1).coerceIn(0, 6)
            val dayName = dayNamesShort.getOrElse(dayOfWeekIdx) { "" }
            val isToday = Formatter.getDayCodeFromTS(dayDt.seconds()) == Formatter.getTodayCode()

            // Header
            val headerView = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(tinyMargin, smallMargin, tinyMargin, smallMargin)
                }
                text = "$dayName\n${dayDt.dayOfMonth}"
                gravity = android.view.Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, headerTextSizePx.toFloat())
                setTextColor(if (isToday) primaryColor else textColor)
                if (isToday) setTypeface(null, Typeface.BOLD)
            }
            binding.weeklyGridDayHeaders.addView(headerView)

            // Column
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(tinyMargin, smallMargin, tinyMargin, 0)
                }
            }

            dayEvents[dayIdx].sortedBy { it.startTS }.forEach { event ->
                val taskMeta = if (taskifyEventsMode && !event.isTask()) {
                    TaskifyHelper.parseTitle(event.title, taskifyModeEnabled = true)
                } else null
                val displayTitle = taskMeta?.cleanTitle ?: event.title

                val itemBinding = WeeklyGridEventItemBinding.inflate(inflater, column, false)
                itemBinding.weeklyEventItemTitle.text = displayTitle
                itemBinding.weeklyEventItemTime.text = if (event.getIsAllDay()) {
                    ctx.getString(R.string.all_day)
                } else {
                    Formatter.getTimeFromTS(ctx, event.startTS)
                }
                itemBinding.weeklyEventItemColorBar.background.applyColorFilter(event.color)

                var newTextColor = textColor
                val adjustAlpha = if (event.isTask()) dimCompletedTasks && event.isTaskCompleted()
                    else dimPastEvents && event.isPastEvent
                if (adjustAlpha) newTextColor = newTextColor.adjustAlpha(MEDIUM_ALPHA)

                if (taskMeta != null) {
                    val isImportant = taskMeta.isImportant
                    val isCompleted = taskMeta.isCompleted

                    itemBinding.weeklyEventItemCheckbox.beVisibleIf(true)
                    itemBinding.weeklyEventItemCheckbox.setImageResource(
                        if (isCompleted) R.drawable.ic_checkbox_checked_vector
                        else R.drawable.ic_checkbox_unchecked_vector
                    )
                    itemBinding.weeklyEventItemCheckbox.applyColorFilter(newTextColor)
                    itemBinding.weeklyEventItemCheckbox.setOnClickListener { toggleTaskifyCompletion(event) }
                    itemBinding.weeklyEventItemImportantImage.beVisibleIf(isImportant)
                    if (isImportant) itemBinding.weeklyEventItemImportantImage.applyColorFilter(newTextColor)
                    itemBinding.weeklyEventItemTitle.typeface = if (isImportant) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

                    if (isCompleted) {
                        val dimmedColor = newTextColor.adjustAlpha(MEDIUM_ALPHA)
                        itemBinding.weeklyEventItemTitle.setTextColor(dimmedColor)
                        itemBinding.weeklyEventItemTitle.paintFlags =
                            itemBinding.weeklyEventItemTitle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                        itemBinding.weeklyEventItemCompletedLine.beVisibleIf(true)
                    } else {
                        itemBinding.weeklyEventItemTitle.setTextColor(newTextColor)
                        itemBinding.weeklyEventItemTitle.paintFlags =
                            itemBinding.weeklyEventItemTitle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        itemBinding.weeklyEventItemCompletedLine.beGone()
                    }
                    itemBinding.weeklyEventItemTime.setTextColor(newTextColor)
                } else {
                    itemBinding.weeklyEventItemCheckbox.beGone()
                    itemBinding.weeklyEventItemCheckbox.setOnClickListener(null)
                    itemBinding.weeklyEventItemCompletedLine.beGone()
                    itemBinding.weeklyEventItemImportantImage.beGone()
                    itemBinding.weeklyEventItemTitle.typeface = Typeface.DEFAULT
                    itemBinding.weeklyEventItemTitle.setTextColor(newTextColor)
                    itemBinding.weeklyEventItemTitle.paintFlags =
                        itemBinding.weeklyEventItemTitle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    itemBinding.weeklyEventItemTime.setTextColor(newTextColor)
                }

                itemBinding.root.setOnClickListener {
                    Intent(ctx, getActivityToOpen(event.isTask())).apply {
                        putExtra(EVENT_ID, event.id)
                        putExtra(EVENT_OCCURRENCE_TS, event.startTS)
                        putExtra(IS_TASK_COMPLETED, event.isTaskCompleted())
                        startActivity(this)
                    }
                }

                column.addView(itemBinding.root)
            }

            binding.weeklyGridColumns.addView(column)
        }
    }

    private fun toggleTaskifyCompletion(event: Event) {
        val taskMeta = TaskifyHelper.parseTitle(event.title, taskifyModeEnabled = true)
        val newTitle = TaskifyHelper.encodeTitle(taskMeta.cleanTitle, taskMeta.isImportant, !taskMeta.isCompleted)
        event.title = newTitle
        ensureBackgroundThread {
            val fullEvent = requireContext().eventsDB.getEventWithId(event.id!!)
            if (fullEvent != null) {
                fullEvent.title = newTitle
                requireContext().eventsHelper.updateEvent(fullEvent, updateAtCalDAV = true, showToasts = false)
            }
            activity?.runOnUiThread { if (isAdded) loadWeekEvents() }
        }
    }

    override fun goToToday() { currentWeekTS = thisWeekTS; loadWeekEvents() }
    override fun showGoToDateDialog() {}
    override fun refreshEvents() { if (::binding.isInitialized) loadWeekEvents() }
    override fun shouldGoToTodayBeVisible() = currentWeekTS != thisWeekTS
    override fun getNewEventDayCode(): String {
        val currentTS = System.currentTimeMillis() / 1000
        return if (currentTS >= currentWeekTS && currentTS < currentWeekTS + WEEK_SECONDS) {
            Formatter.getTodayCode()
        } else {
            Formatter.getDayCodeFromTS(currentWeekTS)
        }
    }
    override fun printView() {}
    override fun getCurrentDate(): DateTime? =
        if (currentWeekTS != 0L) Formatter.getDateTimeFromTS(currentWeekTS) else null
}
