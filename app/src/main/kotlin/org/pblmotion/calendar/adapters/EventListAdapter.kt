package org.pblmotion.calendar.adapters

import android.graphics.Typeface
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import org.pblmotion.calendar.R
import org.pblmotion.calendar.activities.SimpleActivity
import org.pblmotion.calendar.databinding.EventListItemBinding
import org.pblmotion.calendar.databinding.EventListSectionDayBinding
import org.pblmotion.calendar.databinding.EventListSectionMonthBinding
import org.pblmotion.calendar.dialogs.DeleteEventDialog
import org.pblmotion.calendar.extensions.*
import org.pblmotion.calendar.helpers.*
import org.pblmotion.calendar.models.ListEvent
import org.pblmotion.calendar.models.ListItem
import org.pblmotion.calendar.models.ListSectionDay
import org.pblmotion.calendar.models.ListSectionMonth
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.commons.views.MyRecyclerView

class EventListAdapter(
    activity: SimpleActivity, var listItems: ArrayList<ListItem>, val allowLongClick: Boolean, val listener: RefreshRecyclerViewListener?,
    recyclerView: MyRecyclerView, itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    private val allDayString = resources.getString(R.string.all_day)
    private val displayDescription = activity.config.displayDescription
    private val replaceDescription = activity.config.replaceDescription
    private val dimPastEvents = activity.config.dimPastEvents
    private val dimCompletedTasks = activity.config.dimCompletedTasks
    private val taskifyEventsMode = activity.config.taskifyEventsMode
    private val now = getNowSeconds()
    private var use24HourFormat = activity.config.use24HourFormat
    private var currentItemsHash = listItems.hashCode()
    private var isPrintVersion = false
    private val mediumMargin = activity.resources.getDimension(org.fossify.commons.R.dimen.medium_margin).toInt()

    init {
        setupDragListener(true)
        val firstNonPastSectionIndex = listItems.indexOfFirst { it is ListSectionDay && !it.isPastSection }
        if (firstNonPastSectionIndex != -1) {
            activity.runOnUiThread {
                recyclerView.scrollToPosition(firstNonPastSectionIndex)
            }
        }
    }

    override fun getActionMenuId() = R.menu.cab_event_list

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        when (id) {
            R.id.cab_share -> shareEvents()
            R.id.cab_delete -> askConfirmDelete()
        }
    }

    override fun getSelectableItemCount() = listItems.filterIsInstance<ListEvent>().size

    override fun getIsItemSelectable(position: Int) = listItems.getOrNull(position) is ListEvent

    override fun getItemSelectionKey(position: Int) = (listItems.getOrNull(position) as? ListEvent)?.hashCode()

    override fun getItemKeyPosition(key: Int) = listItems.indexOfFirst { (it as? ListEvent)?.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyRecyclerViewAdapter.ViewHolder {
        val layoutInflater = activity.layoutInflater
        val binding = when (viewType) {
            ITEM_SECTION_DAY -> EventListSectionDayBinding.inflate(layoutInflater, parent, false)
            ITEM_SECTION_MONTH -> EventListSectionMonthBinding.inflate(layoutInflater, parent, false)
            else -> EventListItemBinding.inflate(layoutInflater, parent, false)
        }

        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: MyRecyclerViewAdapter.ViewHolder, position: Int) {
        val listItem = listItems[position]
        holder.bindView(listItem, allowSingleClick = true, allowLongClick = allowLongClick && listItem is ListEvent) { itemView, _ ->
            when (listItem) {
                is ListSectionDay -> setupListSectionDay(itemView, listItem)
                is ListEvent -> setupListEvent(itemView, listItem, position)
                is ListSectionMonth -> setupListSectionMonth(itemView, listItem)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = listItems.size

    override fun getItemViewType(position: Int) = when {
        listItems[position] is ListEvent -> ITEM_EVENT
        listItems[position] is ListSectionDay -> ITEM_SECTION_DAY
        else -> ITEM_SECTION_MONTH
    }

    fun toggle24HourFormat(use24HourFormat: Boolean) {
        this.use24HourFormat = use24HourFormat
        notifyDataSetChanged()
    }

    fun updateListItems(newListItems: ArrayList<ListItem>) {
        if (newListItems.hashCode() != currentItemsHash) {
            currentItemsHash = newListItems.hashCode()
            listItems = newListItems.clone() as ArrayList<ListItem>
            recyclerView.resetItemCount()
            notifyDataSetChanged()
            finishActMode()
        }
    }

    fun togglePrintMode() {
        isPrintVersion = !isPrintVersion
        textColor = if (isPrintVersion) {
            resources.getColor(org.fossify.commons.R.color.theme_light_text_color)
        } else {
            activity.getProperTextColor()
        }
        notifyDataSetChanged()
    }

    private fun toggleTaskifyCompletion(listEvent: ListEvent, position: Int) {
        val taskMeta = TaskifyHelper.parseTitle(listEvent.title, taskifyModeEnabled = true)
        val newTitle = TaskifyHelper.encodeTitle(taskMeta.cleanTitle, taskMeta.isImportant, !taskMeta.isCompleted)
        // Optimistic update: change in memory immediately so UI refreshes without waiting for DB
        listEvent.title = newTitle
        notifyItemChanged(position)

        ensureBackgroundThread {
            val fullEvent = activity.eventsDB.getEventWithId(listEvent.id)
            if (fullEvent != null) {
                fullEvent.title = newTitle
                activity.eventsHelper.updateEvent(fullEvent, updateAtCalDAV = true, showToasts = false)
            }
        }
    }

    private fun setupListEvent(view: View, listEvent: ListEvent, position: Int) {
        EventListItemBinding.bind(view).apply {
            val taskMeta = if (taskifyEventsMode && !listEvent.isTask) {
                TaskifyHelper.parseTitle(listEvent.title, taskifyModeEnabled = true)
            } else {
                null
            }

            val displayTitle = taskMeta?.cleanTitle ?: listEvent.title

            eventItemHolder.isSelected = selectedKeys.contains(listEvent.hashCode())
            eventItemHolder.background.applyColorFilter(textColor)
            eventItemTitle.text = displayTitle
            eventItemTitle.checkViewStrikeThrough(listEvent.shouldStrikeThrough())
            eventItemTime.text = if (listEvent.isAllDay) allDayString else Formatter.getTimeFromTS(activity, listEvent.startTS)
            if (listEvent.startTS != listEvent.endTS) {
                if (!listEvent.isAllDay) {
                    eventItemTime.text = "${eventItemTime.text} - ${Formatter.getTimeFromTS(activity, listEvent.endTS)}"
                }

                val startCode = Formatter.getDayCodeFromTS(listEvent.startTS)
                val endCode = Formatter.getDayCodeFromTS(listEvent.endTS)
                if (startCode != endCode) {
                    eventItemTime.text = "${eventItemTime.text} (${Formatter.getDateDayTitle(endCode)})"
                }
            }

            eventItemDescription.text = if (replaceDescription) listEvent.location else listEvent.description.replace("\n", " ")
            eventItemDescription.beVisibleIf(displayDescription && eventItemDescription.text.isNotEmpty())
            eventItemColorBar.background.applyColorFilter(listEvent.color)

            var newTextColor = textColor
            if (listEvent.isAllDay || listEvent.startTS <= now && listEvent.endTS <= now) {
                if (listEvent.isAllDay && Formatter.getDayCodeFromTS(listEvent.startTS) == Formatter.getDayCodeFromTS(now) && !isPrintVersion) {
                    newTextColor = properPrimaryColor
                }

                val adjustAlpha = if (listEvent.isTask) {
                    dimCompletedTasks && listEvent.isTaskCompleted
                } else {
                    dimPastEvents && listEvent.isPastEvent && !isPrintVersion
                }
                if (adjustAlpha) {
                    newTextColor = newTextColor.adjustAlpha(MEDIUM_ALPHA)
                }
            } else if (listEvent.startTS <= now && listEvent.endTS >= now && !isPrintVersion) {
                newTextColor = properPrimaryColor
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
                    toggleTaskifyCompletion(listEvent, position)
                }
                eventItemTaskImage.beGone()

                // Show important icon when applicable
                eventItemImportantImage.beVisibleIf(isImportant)
                eventItemImportantImage.applyColorFilter(newTextColor)

                // Apply bold for important tasks
                eventItemTitle.typeface = if (isImportant) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

                if (isCompleted) {
                    val dimmedColor = newTextColor.adjustAlpha(MEDIUM_ALPHA)
                    eventItemTitle.setTextColor(dimmedColor)
                    eventItemTitle.paintFlags = eventItemTitle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
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
                eventItemTaskImage.beVisibleIf(listEvent.isTask)

                val startMargin = if (listEvent.isTask) 0 else mediumMargin
                (eventItemTitle.layoutParams as ConstraintLayout.LayoutParams).marginStart = startMargin
            }
        }
    }

    private fun setupListSectionDay(view: View, listSectionDay: ListSectionDay) {
        EventListSectionDayBinding.bind(view).eventSectionTitle.apply {
            text = listSectionDay.title
            val dayColor = if (listSectionDay.isToday) properPrimaryColor else textColor
            setTextColor(dayColor)
        }
    }

    private fun setupListSectionMonth(view: View, listSectionMonth: ListSectionMonth) {
        EventListSectionMonthBinding.bind(view).eventSectionTitle.apply {
            text = listSectionMonth.title
            setTextColor(properPrimaryColor)
        }
    }

    private fun shareEvents() = activity.shareEvents(getSelectedEventIds())

    private fun getSelectedEventIds() =
        listItems.filter { it is ListEvent && selectedKeys.contains(it.hashCode()) }.map { (it as ListEvent).id }.toMutableList() as ArrayList<Long>

    private fun askConfirmDelete() {
        val eventIds = getSelectedEventIds()
        val eventsToDelete = listItems.filter { selectedKeys.contains((it as? ListEvent)?.hashCode()) } as List<ListEvent>
        val timestamps = eventsToDelete.mapNotNull { (it as? ListEvent)?.startTS }

        val hasRepeatableEvent = eventsToDelete.any { it.isRepeatable }
        DeleteEventDialog(activity, eventIds, hasRepeatableEvent) {
            listItems.removeAll(eventsToDelete)

            ensureBackgroundThread {
                val nonRepeatingEventIDs = eventsToDelete.filter { !it.isRepeatable }.map { it.id }.toMutableList()
                activity.eventsHelper.deleteEvents(nonRepeatingEventIDs, true)

                val repeatingEventIDs = eventsToDelete.filter { it.isRepeatable }.map { it.id }
                activity.handleEventDeleting(repeatingEventIDs, timestamps, it)
                activity.runOnUiThread {
                    listener?.refreshItems()
                    finishActMode()
                }
            }
        }
    }
}
