package org.pblmotion.calendar.adapters

import android.graphics.Typeface
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import org.pblmotion.calendar.R
import org.pblmotion.calendar.activities.SimpleActivity
import org.pblmotion.calendar.databinding.EventListItemBinding
import org.pblmotion.calendar.dialogs.DeleteEventDialog
import org.pblmotion.calendar.extensions.*
import org.pblmotion.calendar.helpers.Formatter
import org.pblmotion.calendar.helpers.TaskifyHelper
import org.pblmotion.calendar.models.Event
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView

class DayEventsAdapter(activity: SimpleActivity, val events: ArrayList<Event>, recyclerView: MyRecyclerView, var dayCode: String, itemClick: (Any) -> Unit) :
    MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    private val allDayString = resources.getString(R.string.all_day)
    private val displayDescription = activity.config.displayDescription
    private val replaceDescriptionWithLocation = activity.config.replaceDescription
    private val dimPastEvents = activity.config.dimPastEvents
    private val dimCompletedTasks = activity.config.dimCompletedTasks
    private val taskifyEventsMode = activity.config.taskifyEventsMode
    private var isPrintVersion = false
    private val mediumMargin = activity.resources.getDimension(org.fossify.commons.R.dimen.medium_margin).toInt()

    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_day

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        when (id) {
            R.id.cab_share -> shareEvents()
            R.id.cab_delete -> askConfirmDelete()
        }
    }

    override fun getSelectableItemCount() = events.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = events.getOrNull(position)?.id?.toInt()

    override fun getItemKeyPosition(key: Int) = events.indexOfFirst { it.id?.toInt() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(
            view = EventListItemBinding.inflate(activity.layoutInflater, parent, false).root
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]
        holder.bindView(event, allowSingleClick = true, allowLongClick = true) { itemView, _ ->
            setupView(itemView, event, position)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = events.size

    fun togglePrintMode() {
        isPrintVersion = !isPrintVersion
        textColor = if (isPrintVersion) {
            resources.getColor(org.fossify.commons.R.color.theme_light_text_color)
        } else {
            activity.getProperTextColor()
        }

        notifyDataSetChanged()
    }

    private fun toggleTaskifyCompletion(event: Event, position: Int) {
        val taskMeta = TaskifyHelper.parseTitle(event.title, taskifyModeEnabled = true)
        val newTitle = TaskifyHelper.encodeTitle(taskMeta.cleanTitle, taskMeta.isImportant, !taskMeta.isCompleted)
        event.title = newTitle
        notifyItemChanged(position)

        ensureBackgroundThread {
            val fullEvent = activity.eventsDB.getEventWithId(event.id!!)
            if (fullEvent != null) {
                fullEvent.title = newTitle
                activity.eventsHelper.updateEvent(fullEvent, updateAtCalDAV = true, showToasts = false)
            }
        }
    }

    private fun setupView(view: View, event: Event, position: Int) {
        EventListItemBinding.bind(view).apply {
            val taskMeta = if (taskifyEventsMode && !event.isTask()) {
                TaskifyHelper.parseTitle(event.title, taskifyModeEnabled = true)
            } else {
                null
            }

            val displayTitle = taskMeta?.cleanTitle ?: event.title

            eventItemHolder.isSelected = selectedKeys.contains(event.id?.toInt())
            eventItemHolder.background.applyColorFilter(textColor)
            eventItemTitle.text = displayTitle
            eventItemTitle.checkViewStrikeThrough(event.shouldStrikeThrough())
            eventItemTime.text = if (event.getIsAllDay()) allDayString else Formatter.getTimeFromTS(activity, event.startTS)
            if (event.startTS != event.endTS) {
                val startDayCode = Formatter.getDayCodeFromTS(event.startTS)
                val endDayCode = Formatter.getDayCodeFromTS(event.endTS)
                val startDate = Formatter.getDayTitle(activity, startDayCode, false)
                val endDate = Formatter.getDayTitle(activity, endDayCode, false)
                val startDayString = if (startDayCode != dayCode) " ($startDate)" else ""
                if (!event.getIsAllDay()) {
                    val endTimeString = Formatter.getTimeFromTS(activity, event.endTS)
                    val endDayString = if (endDayCode != dayCode) " ($endDate)" else ""
                    eventItemTime.text = "${eventItemTime.text}$startDayString - $endTimeString$endDayString"
                } else {
                    val endDayString = if (endDayCode != dayCode) " - ($endDate)" else ""
                    eventItemTime.text = "${eventItemTime.text}$startDayString$endDayString"
                }
            }

            eventItemDescription.text = if (replaceDescriptionWithLocation) event.location else event.description.replace("\n", " ")
            eventItemDescription.beVisibleIf(displayDescription && eventItemDescription.text.isNotEmpty())
            eventItemColorBar.background.applyColorFilter(event.color)

            var newTextColor = textColor

            val adjustAlpha = if (event.isTask()) {
                dimCompletedTasks && event.isTaskCompleted()
            } else {
                dimPastEvents && event.isPastEvent && !isPrintVersion
            }

            if (adjustAlpha) {
                newTextColor = newTextColor.adjustAlpha(MEDIUM_ALPHA)
            }

            if (taskMeta != null) {
                val isImportant = taskMeta.isImportant
                val isCompleted = taskMeta.isCompleted

                // Show completion checkbox, hide task image
                eventItemCheckbox.beVisibleIf(true)
                eventItemCheckbox.setImageResource(
                    if (isCompleted) R.drawable.ic_checkbox_checked_vector
                    else R.drawable.ic_checkbox_unchecked_vector
                )
                eventItemCheckbox.applyColorFilter(newTextColor)
                eventItemCheckbox.setOnClickListener {
                    toggleTaskifyCompletion(event, position)
                }
                eventItemTaskImage.beGone()

                // Show important icon when applicable
                eventItemImportantImage.beVisibleIf(isImportant)
                eventItemImportantImage.applyColorFilter(newTextColor)

                // Apply bold for important tasks
                eventItemTitle.typeface = if (isImportant) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

                if (isCompleted) {
                    // Dim text for completed tasks
                    val dimmedColor = newTextColor.adjustAlpha(MEDIUM_ALPHA)
                    eventItemTitle.setTextColor(dimmedColor)
                    eventItemTitle.paintFlags = eventItemTitle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    // Show red strikethrough overlay
                    eventItemCompletedLine.beVisibleIf(true)
                } else {
                    eventItemTitle.setTextColor(newTextColor)
                    eventItemTitle.paintFlags = eventItemTitle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    eventItemCompletedLine.beGone()
                }

                eventItemTime.setTextColor(newTextColor)
                eventItemDescription.setTextColor(newTextColor)
                (eventItemTitle.layoutParams as ConstraintLayout.LayoutParams).marginStart = 0
            } else {
                // Normal event or actual task — hide taskify controls
                eventItemCheckbox.beGone()
                eventItemCheckbox.setOnClickListener(null)
                eventItemCompletedLine.beGone()
                eventItemTitle.typeface = Typeface.DEFAULT
                eventItemImportantImage.beGone()
                eventItemTitle.setTextColor(newTextColor)
                eventItemTitle.paintFlags = eventItemTitle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                eventItemTime.setTextColor(newTextColor)
                eventItemDescription.setTextColor(newTextColor)
                eventItemTaskImage.applyColorFilter(newTextColor)
                eventItemTaskImage.beVisibleIf(event.isTask())

                val startMargin = if (event.isTask()) 0 else mediumMargin
                (eventItemTitle.layoutParams as ConstraintLayout.LayoutParams).marginStart = startMargin
            }
        }
    }

    private fun shareEvents() = activity.shareEvents(selectedKeys.distinct().map { it.toLong() })

    private fun askConfirmDelete() {
        val eventIds = selectedKeys.map { it.toLong() }.toMutableList()
        val eventsToDelete = events.filter { selectedKeys.contains(it.id?.toInt()) }
        val timestamps = eventsToDelete.map { it.startTS }
        val positions = getSelectedItemPositions()

        val hasRepeatableEvent = eventsToDelete.any { it.repeatInterval > 0 }
        DeleteEventDialog(activity, eventIds, hasRepeatableEvent) { it ->
            events.removeAll(eventsToDelete)

            ensureBackgroundThread {
                val nonRepeatingEventIDs = eventsToDelete.asSequence().filter { it.repeatInterval == 0 }.mapNotNull { it.id }.toMutableList()
                activity.eventsHelper.deleteEvents(nonRepeatingEventIDs, true)

                val repeatingEventIDs = eventsToDelete.asSequence().filter { it.repeatInterval != 0 }.mapNotNull { it.id }.toList()
                activity.handleEventDeleting(repeatingEventIDs, timestamps, it)
                activity.runOnUiThread {
                    removeSelectedItems(positions)
                }
            }
        }
    }
}
