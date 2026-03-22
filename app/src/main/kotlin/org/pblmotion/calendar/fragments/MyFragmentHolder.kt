package org.pblmotion.calendar.fragments

import android.graphics.Color
import android.widget.DatePicker
import androidx.fragment.app.Fragment
import org.pblmotion.calendar.databinding.DatePickerDarkBinding
import org.pblmotion.calendar.databinding.DatePickerLightBinding
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.joda.time.DateTime

/**
 * Abstract base class for all calendar view fragment holders.
 *
 * Each concrete subclass is responsible for one of the supported view modes
 * (see `*_VIEW` constants in [org.pblmotion.calendar.helpers.Constants]):
 *
 * - [DayFragmentsHolder] — Daily scroll list
 * - [WeekFragmentsHolder] — Weekly timeline
 * - [MonthFragmentsHolder] — Monthly grid
 * - [MonthDayFragmentsHolder] — Combined monthly + daily view
 * - [YearFragmentsHolder] — Year overview
 * - [EventListFragment] — Infinite agenda list
 * - [WeeklyGridFragment] — 7-day grid (Taskify-aware)
 *
 * [MainActivity][org.pblmotion.calendar.activities.MainActivity] always hosts exactly one
 * `MyFragmentHolder` at a time, swapping it when the user selects a different view.
 */
abstract class MyFragmentHolder : Fragment() {

    /** The view mode constant this fragment represents (one of the `*_VIEW` values). */
    abstract val viewType: Int

    /** Scrolls or navigates the view so that today's date is visible and selected. */
    abstract fun goToToday()

    /**
     * Opens the "Go to date" date-picker dialog so the user can jump to an arbitrary date.
     *
     * Implementations should call [getDatePickerView] to obtain a theme-appropriate
     * [DatePicker] widget.
     */
    abstract fun showGoToDateDialog()

    /**
     * Reloads events from the database and refreshes the displayed content.
     *
     * Called by [MainActivity][org.pblmotion.calendar.activities.MainActivity] when external
     * data changes occur (CalDAV sync complete, event edited in another screen, etc.).
     */
    abstract fun refreshEvents()

    /**
     * Returns whether the "Go to today" toolbar button should currently be visible.
     *
     * Typically returns `true` whenever the displayed date range does not include today.
     */
    abstract fun shouldGoToTodayBeVisible(): Boolean

    /**
     * Returns the day code (format `YYYYMMdd`) that should be pre-filled when the user taps
     * the FAB to create a new event.
     *
     * Most implementations return the currently selected day code; leaf views that do not
     * track a selected day may fall back to [org.pblmotion.calendar.helpers.Formatter.getTodayCode].
     */
    abstract fun getNewEventDayCode(): String

    /**
     * Triggers a print of the current view.
     *
     * Implementations that do not support printing may leave the body empty.
     */
    abstract fun printView()

    /**
     * Returns the [DateTime] currently visible in this fragment, or `null` if no specific
     * date is associated (e.g., an infinite-scroll event list).
     *
     * Used by [MainActivity][org.pblmotion.calendar.activities.MainActivity] to calculate the
     * correct date when the user switches between view modes.
     */
    abstract fun getCurrentDate(): DateTime?

    /**
     * Returns a theme-appropriate [DatePicker] widget for use in "Go to date" dialogs.
     *
     * The picker is dark when the background is light, and light when the background is dark,
     * following the contrast of the app's background colour.
     */
    fun getDatePickerView(): DatePicker {
        return if (requireActivity().getProperBackgroundColor().getContrastColor() == Color.WHITE) {
            DatePickerDarkBinding.inflate(layoutInflater).datePicker
        } else {
            DatePickerLightBinding.inflate(layoutInflater).datePicker
        }
    }
}
