package org.pblmotion.calendar.helpers

import org.pblmotion.calendar.models.TaskMeta

/**
 * Stateless helper that handles the **Taskify Events Mode** suffix encoding scheme.
 *
 * ## Encoding format
 * Taskify metadata is appended to a regular event title using bracket-enclosed suffixes:
 *
 * | Suffix   | Meaning                   |
 * |----------|---------------------------|
 * | (none)   | Normal event / no flags   |
 * | `[!]`    | Important, not completed  |
 * | `[C]`    | Completed, not important  |
 * | `[!/C]`  | Important **and** completed |
 *
 * Example titles stored in the database:
 * ```
 * "Buy groceries"        → plain event
 * "Buy groceries [!]"    → important
 * "Buy groceries [C]"    → completed
 * "Buy groceries [!/C]"  → important + completed
 * ```
 *
 * ## Usage
 * ```kotlin
 * // Decode:
 * val meta = TaskifyHelper.parseTitle(event.title, taskifyModeEnabled = true)
 *
 * // Re-encode after toggling completion:
 * event.title = TaskifyHelper.encodeTitle(meta.cleanTitle, meta.isImportant, !meta.isCompleted)
 *
 * // Strip suffix for display only:
 * val label = TaskifyHelper.getDisplayTitle(event.title)
 * ```
 */
object TaskifyHelper {

    /**
     * Regex to parse task suffixes appended to event titles.
     *
     * - Group 1: clean title (everything before the optional suffix)
     * - Group 2: the suffix itself — one of `[!/C]`, `[!]`, or `[C]`.
     *
     * **Note:** the order of alternation matters — `[!/C]` must be tried before `[!]` and
     * `[C]` to avoid a partial match against the combined form.
     */
    private val SUFFIX_REGEX = Regex("""^(.*?)(\s*\[!/C\]|\s*\[!\]|\s*\[C\])?$""")

    /**
     * Parses [rawTitle] and extracts Taskify metadata.
     *
     * @param rawTitle The full event title as stored in the database (may contain a suffix).
     * @param taskifyModeEnabled Whether Taskify Events Mode is currently enabled. This value is
     *   forwarded into [TaskMeta.isTask] so callers know whether to render task UI.
     * @return A [TaskMeta] with the decoded flags and the clean title (no suffix).
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
     * Encodes task metadata back into a complete event title by appending the appropriate suffix.
     *
     * @param cleanTitle The human-readable title **without** any existing suffix.
     * @param isImportant Whether the event should be marked as important.
     * @param isCompleted Whether the event should be marked as completed.
     * @return The encoded title ready to be stored in [org.pblmotion.calendar.models.Event.title].
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
     * Returns the clean display title by stripping any Taskify suffix.
     *
     * This is a convenience wrapper around [parseTitle] for callers that only need the
     * stripped title and do not need the full [TaskMeta].
     *
     * @param rawTitle The raw event title, which may or may not contain a suffix.
     * @return The title with any `[!]`, `[C]`, or `[!/C]` suffix removed.
     */
    fun getDisplayTitle(rawTitle: String): String {
        return SUFFIX_REGEX.find(rawTitle)?.groupValues?.get(1)?.trim() ?: rawTitle.trim()
    }
}
