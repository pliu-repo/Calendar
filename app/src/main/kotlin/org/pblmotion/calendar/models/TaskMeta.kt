package org.pblmotion.calendar.models

data class TaskMeta(
    val isTask: Boolean,
    val isImportant: Boolean,
    val isCompleted: Boolean,
    val cleanTitle: String
)
