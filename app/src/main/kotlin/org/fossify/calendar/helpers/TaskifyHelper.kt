package org.fossify.calendar.helpers

import org.fossify.calendar.models.TaskMeta

object TaskifyHelper {

    private val SUFFIX_REGEX = Regex("""^(.*?)(\s*\[!/C\]|\s*\[!\]|\s*\[C\])?$""")

    /**
     * Parses a raw event title and extracts task metadata (importance, completion, clean title).
     * Works for both Taskify Events Mode and legacy Caros-style encoded events.
     */
    fun parseTitle(rawTitle: String, taskifyModeEnabled: Boolean): TaskMeta {
        val matchResult = SUFFIX_REGEX.find(rawTitle)
        val cleanTitle = matchResult?.groupValues?.get(1)?.trim() ?: rawTitle.trim()
        val suffix = matchResult?.groupValues?.get(2)?.trim() ?: ""

        val isImportant = suffix == "[!]" || suffix == "[!/C]"
        val isCompleted = suffix == "[C]" || suffix == "[!/C]"

        return TaskMeta(
            isTask = taskifyModeEnabled,
            isImportant = isImportant,
            isCompleted = isCompleted,
            cleanTitle = cleanTitle
        )
    }

    /**
     * Encodes task metadata back into a title with the appropriate suffix.
     */
    fun encodeTitle(cleanTitle: String, isImportant: Boolean, isCompleted: Boolean): String {
        return when {
            isImportant && isCompleted -> "$cleanTitle [!/C]"
            isImportant -> "$cleanTitle [!]"
            isCompleted -> "$cleanTitle [C]"
            else -> cleanTitle
        }
    }

    /**
     * Returns the display title (without suffix) for showing in the UI.
     */
    fun getDisplayTitle(rawTitle: String): String {
        return SUFFIX_REGEX.find(rawTitle)?.groupValues?.get(1)?.trim() ?: rawTitle.trim()
    }
}
