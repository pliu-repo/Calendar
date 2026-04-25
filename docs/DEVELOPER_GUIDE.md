# Calendar — Developer Guide

This guide explains the architecture, key abstractions, and best practices for working with
the `org.pblmotion.calendar` codebase. It is aimed at developers who want to understand how
the app is structured, add new features, or adjust existing behaviour.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Project Structure](#2-project-structure)
3. [Build Variants & Flavors](#3-build-variants--flavors)
4. [Data Model](#4-data-model)
5. [Database Layer (Room)](#5-database-layer-room)
6. [Configuration System](#6-configuration-system)
7. [View Modes & Fragments](#7-view-modes--fragments)
8. [Adapters](#8-adapters)
9. [Taskify Events Mode](#9-taskify-events-mode)
10. [CalDAV Sync](#10-caldav-sync)
11. [Notifications & Reminders](#11-notifications--reminders)
12. [Widgets](#12-widgets)
13. [Key Design Patterns](#13-key-design-patterns)
14. [How To: Add a New View Mode](#14-how-to-add-a-new-view-mode)
15. [How To: Add a New Setting](#15-how-to-add-a-new-setting)
16. [How To: Modify Event Rendering](#16-how-to-modify-event-rendering)

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                      Activities                     │
│  MainActivity · EventActivity · TaskActivity        │
│  SettingsActivity · WidgetConfigureActivities …     │
└────────────────┬────────────────────────────────────┘
                 │ hosts
┌────────────────▼────────────────────────────────────┐
│              Fragment Holders                       │
│  (DayFragmentsHolder, MonthFragmentsHolder, …)      │
│  Each holder manages a ViewPager of leaf fragments  │
└────────────────┬────────────────────────────────────┘
                 │ pages of
┌────────────────▼────────────────────────────────────┐
│            Leaf Fragments / Views                   │
│  DayFragment · WeekFragment · MonthFragment         │
│  WeeklyGridFragment · EventListFragment · …         │
│  MonthView (custom View) · WeeklyViewGrid           │
└────────────────┬────────────────────────────────────┘
                 │ calls
┌────────────────▼────────────────────────────────────┐
│              Helpers & Extensions                   │
│  EventsHelper  · CalDAVHelper · TaskifyHelper       │
│  Formatter     · Config       · Context extensions  │
└────────────────┬────────────────────────────────────┘
                 │ reads/writes
┌────────────────▼────────────────────────────────────┐
│                Room Database (events.db)            │
│  EventsDao · CalendarsDao · WidgetsDao · TasksDao   │
└─────────────────────────────────────────────────────┘
```

The app is a standard single-`Activity` + multiple-`Fragment` Android app backed by a Room
database. Most business logic lives in `EventsHelper` and `CalDAVHelper`. All database access
**must** be off the main thread (use `ensureBackgroundThread { … }` from fossify-commons).

---

## 2. Project Structure

```
app/src/main/kotlin/org/pblmotion/calendar/
│
├── activities/          # Android Activities (screens)
│   ├── MainActivity.kt          ← Entry point; hosts the current fragment/view
│   ├── EventActivity.kt         ← Create / edit an event
│   ├── TaskActivity.kt          ← Create / edit a task
│   ├── SettingsActivity.kt      ← All app preferences
│   ├── SimpleActivity.kt        ← Base activity (CalDAV observer, helpers)
│   ├── SplashActivity.kt        ← Routes deep-links to the correct screen
│   └── Widget*ConfigureActivity ← Widget configuration screens
│
├── adapters/            # RecyclerView / ViewPager adapters
│   ├── DayEventsAdapter.kt      ← Event list in Daily View
│   ├── EventListAdapter.kt      ← Event list in Event List (Agenda) View
│   ├── MyDayPagerAdapter.kt     ← ViewPager pages for DayFragmentsHolder
│   ├── MyMonthPagerAdapter.kt   ← ViewPager pages for MonthFragmentsHolder
│   ├── MyWeekPagerAdapter.kt    ← ViewPager pages for WeekFragmentsHolder
│   └── …
│
├── databases/
│   └── EventsDatabase.kt        ← Room database singleton, migrations
│
├── dialogs/             # Dialog fragments and alert dialogs
│
├── extensions/          # Kotlin extension functions
│   ├── Context.kt               ← DB accessors, notification scheduling, event loading
│   ├── Event.kt                 ← taskMeta property, allDay conversion, strikethrough
│   ├── Activity.kt              ← shareEvents, duplicate helpers
│   ├── DateTime.kt              ← seconds(), roundTo helpers
│   ├── TextView.kt              ← checkViewStrikeThrough, applyTaskifyCompletedStyle
│   └── …
│
├── fragments/           # UI fragment pages
│   ├── MyFragmentHolder.kt      ← Abstract base for all holder fragments
│   ├── DayFragmentsHolder.kt    ← Daily view pager holder
│   ├── WeekFragmentsHolder.kt   ← Weekly view pager holder
│   ├── MonthFragmentsHolder.kt  ← Monthly view pager holder
│   ├── WeeklyGridFragment.kt    ← 7-day grid (Taskify-aware)
│   ├── EventListFragment.kt     ← Infinite-scroll agenda list
│   └── …
│
├── helpers/             # Pure-logic helpers and constants
│   ├── Constants.kt             ← All constants + top-level helper functions
│   ├── Config.kt                ← SharedPreferences wrappers
│   ├── EventsHelper.kt          ← Insert/update/delete events, calendars
│   ├── CalDAVHelper.kt          ← CalDAV read/write via ContentProvider
│   ├── Formatter.kt             ← Date/time formatting
│   ├── TaskifyHelper.kt         ← Suffix-based task encoding/decoding
│   ├── IcsImporter.kt           ← ICS file import
│   ├── IcsExporter.kt           ← ICS file export
│   └── Converters.kt            ← Room TypeConverters
│
├── interfaces/          # Room DAO interfaces
│   ├── EventsDao.kt
│   ├── CalendarsDao.kt
│   ├── TasksDao.kt
│   └── WidgetsDao.kt
│
├── models/              # Data classes
│   ├── Event.kt                 ← Primary entity (events + tasks share the table)
│   ├── CalendarEntity.kt        ← A local or CalDAV calendar
│   ├── ListEvent.kt             ← Projection of Event for the list view
│   ├── MonthViewEvent.kt        ← Projection for the month grid
│   ├── TaskMeta.kt              ← Decoded task suffix metadata
│   └── …
│
├── receivers/           # BroadcastReceivers (alarm, CalDAV, backup)
├── services/            # Background Services (widget, snooze, mark-complete)
├── views/               # Custom Views (MonthView, WeeklyViewGrid, …)
└── jobs/                # WorkManager jobs (AppStartupWorker, CalDAVUpdateListener)
```

---

## 3. Build Variants & Flavors

The Gradle build defines **three product flavors** on the `variants` dimension:

| Flavor | Purpose |
|--------|---------|
| `core` | Default/generic build |
| `foss`  | F-Droid / fully open-source build |
| `gplay` | Google Play build |

Key build config properties live in `gradle.properties`:

```properties
APP_ID=org.pblmotion.calendar
VERSION_CODE=…
VERSION_NAME=…
```

The `applicationId` and Room `namespace` are derived from `APP_ID`. The `debug` build type
appends `.debug` to the application ID, allowing debug and release builds to co-exist on a device.

---

## 4. Data Model

### 4.1 `Event` (primary entity)

`Event` is stored in the `events` Room table and represents **both events and tasks** (the
`type` column distinguishes them: `TYPE_EVENT = 0`, `TYPE_TASK = 1`).

| Field | Description |
|-------|-------------|
| `id` | Auto-generated primary key |
| `startTS` / `endTS` | Unix timestamps in **seconds** |
| `title` | Human-readable title; may contain Taskify suffix (see §9) |
| `flags` | Bitmask: `FLAG_ALL_DAY`, `FLAG_IS_IN_PAST`, `FLAG_MISSING_YEAR`, `FLAG_TASK_COMPLETED` |
| `calendarId` | Foreign key into the `calendars` table |
| `source` | Source identifier: `"simple-calendar"`, `"Caldav-<id>"`, `"contact-birthday"`, … |
| `repeatInterval` / `repeatRule` / `repeatLimit` | Recurrence fields |
| `repetitionExceptions` | List of day codes for skipped occurrences |

**Key methods on `Event`:**

```kotlin
event.isTask()           // type == TYPE_TASK
event.isTaskCompleted()  // isTask() && flags has FLAG_TASK_COMPLETED
event.getIsAllDay()      // flags has FLAG_ALL_DAY
event.getReminders()     // returns non-OFF reminders as List<Reminder>
event.addIntervalTime()  // advances startTS/endTS by one recurrence step
event.updateIsPastEvent()// refreshes FLAG_IS_IN_PAST
event.shouldStrikeThrough() // extension — true when completed/declined/cancelled
event.taskMeta           // extension property — decoded TaskMeta
```

### 4.2 `CalendarEntity`

Represents a local or CalDAV-backed calendar. Stored in the `calendars` table. The
`caldavCalendarId` field is `0` for local calendars and the CalDAV server's calendar ID for
synced calendars.

### 4.3 `ListEvent`

A lightweight projection of an `Event` used by `EventListAdapter`. Includes pre-computed
`isImportant` and `isTaskCompleted` booleans so the adapter does not need to re-parse suffixes
for every item during scroll.

### 4.4 `MonthViewEvent`

A projection used by `MonthView` (the custom canvas-drawn month grid). Carries span information
(`startDayIndex`, `daysCnt`) for multi-day events.

### 4.5 `TaskMeta`

```kotlin
data class TaskMeta(
    val isTask: Boolean,
    val isImportant: Boolean,
    val isCompleted: Boolean,
    val cleanTitle: String   // title with any suffix stripped
)
```

Produced by `TaskifyHelper.parseTitle()` or the `Event.taskMeta` extension property.

---

## 5. Database Layer (Room)

### 5.1 Singleton

`EventsDatabase` is a Room singleton accessed through extension properties on `Context`:

```kotlin
val Context.eventsDB: EventsDao     // events table
val Context.calendarsDB: CalendarsDao
val Context.widgetsDB: WidgetsDao
val Context.completedTasksDB: TasksDao
```

Always use these extension properties rather than creating new instances.

### 5.2 Migrations

Migrations live as `Migration` objects inside `EventsDatabase.kt`. The current schema version
is **12**. When you change the schema (add a column, new table, etc.):

1. Increment the `version` in `@Database(version = …)`.
2. Add a `Migration(oldVersion, newVersion)` object that issues the corresponding SQL.
3. Register it with `.addMigrations(YOUR_MIGRATION)` in `getInstance()`.

Room will fail to open the database if a migration is missing, so never skip this step.

### 5.3 TypeConverters

`Converters.kt` converts Kotlin types that Room does not support natively:

- `List<String>` (repetition exceptions) ↔ `String` (comma-separated)
- `List<Attendee>` ↔ `String` (JSON)
- `List<Reminder>` is stored as individual `reminder_N_minutes` / `reminder_N_type` columns

---

## 6. Configuration System

All user preferences are stored in `SharedPreferences` via the `Config` class. Obtain the
singleton with the `Context.config` extension property:

```kotlin
val ctx: Context = …
val storedView = ctx.config.storedView          // current view mode
val taskifyOn  = ctx.config.taskifyEventsMode   // Taskify Events Mode toggle
```

Every preference has a backing constant in `Constants.kt` (e.g., `TASKIFY_EVENTS_MODE = "taskify_events_mode"`).

**To add a new preference:**
1. Add a `const val MY_PREF = "my_pref"` to `Constants.kt`.
2. Add a `var myPref: Boolean` (or `Int`, `String`, …) property to `Config.kt` with a
   get/set that reads/writes via `prefs`.
3. Wire it up in `SettingsActivity.kt` and the corresponding XML layout.

See §15 for a step-by-step walkthrough.

---

## 7. View Modes & Fragments

The active view mode is stored as an `Int` in `Config.storedView`. The constants are:

| Constant | Value | Fragment Holder |
|----------|-------|-----------------|
| `MONTHLY_VIEW` | 1 | `MonthFragmentsHolder` |
| `YEARLY_VIEW` | 2 | `YearFragmentsHolder` |
| `EVENTS_LIST_VIEW` | 3 | `EventListFragment` |
| `WEEKLY_VIEW` | 4 | `WeekFragmentsHolder` |
| `DAILY_VIEW` | 5 | `DayFragmentsHolder` |
| `MONTHLY_DAILY_VIEW` | 7 | `MonthDayFragmentsHolder` |
| `WEEKLY_GRID_VIEW` | 8 | `WeeklyGridFragment` (leaf, not pager) |

### 7.1 `MyFragmentHolder` (abstract base)

All view fragment holders extend `MyFragmentHolder`. You must implement:

```kotlin
abstract val viewType: Int               // one of the *_VIEW constants
abstract fun goToToday()                 // scroll/jump to today's date
abstract fun showGoToDateDialog()        // open the date-picker dialog
abstract fun refreshEvents()             // reload events from DB
abstract fun shouldGoToTodayBeVisible(): Boolean
abstract fun getNewEventDayCode(): String // day code for FAB "new event"
abstract fun printView()                 // trigger print
abstract fun getCurrentDate(): DateTime?
```

### 7.2 Pager-based holders vs. leaf fragments

Most views (day, week, month) use a `ViewPager` that pre-fills ~251 pages centred on today.
The pager adapter creates a leaf fragment for each page with a `DAY_CODE` bundle argument.

`WeeklyGridFragment` is an exception: it is a **leaf fragment** (not a pager). It loads all
seven days of the selected week in a single pass.

### 7.3 `WeeklyGridFragment` layout model

`WeeklyGridFragment` renders the selected week with `fragment_weekly_grid.xml` and a day-column
sub-layout in `weekly_grid_day_column.xml`.

The layout is intentionally viewport-filling:

- `weekly_grid_content` uses `match_parent` height inside a `ScrollView` with `fillViewport="true"`
- the week is split into **four weighted rows** (Sun/Mon, Tue/Wed, Thu/Fri, Sat)
- each row uses equal `layout_weight`, so every row gets the same height
- day columns use `match_parent` height so sibling cells in a row remain visually aligned
- the day-events container uses `0dp` height plus `layout_weight="1"` so the cell stretches to
  the bottom even when a day has only a few events

This is the place to update if the compact weekly grid should change from a full-height layout to
an intrinsic-content layout in the future.

### 7.4 Switching views

`MainActivity.updateViewPager()` creates the correct `MyFragmentHolder` subclass and replaces
the current fragment. `showViewDialog()` presents the radio list. Both operate on the view
constants above.

---

## 8. Adapters

### 8.1 `DayEventsAdapter`

Used inside `DayFragment` for the daily scroll list. Inflates `event_list_item.xml`. Supports:

- Taskify Events Mode (checkbox on right, important icon on left, strike-through)
- Context-action-bar: share and delete

### 8.2 `EventListAdapter`

Used inside `EventListFragment` for the infinite agenda list. Handles mixed item types:

| Item type | Model class | View binding |
|-----------|-------------|--------------|
| `ITEM_EVENT` | `ListEvent` | `EventListItemBinding` |
| `ITEM_SECTION_DAY` | `ListSectionDay` | `EventListSectionDayBinding` |
| `ITEM_SECTION_MONTH` | `ListSectionMonth` | `EventListSectionMonthBinding` |

### 8.3 Adding a new item type

1. Create a new `data class MyItem : ListItem()` in `models/`.
2. Add a `ITEM_MY_TYPE` constant to `Constants.kt`.
3. Override `getItemViewType()` in `EventListAdapter` and inflate the correct binding.

---

## 9. Taskify Events Mode

**Taskify Events Mode** is a custom feature that lets users treat regular calendar events as
actionable tasks by encoding metadata in the event's title using a suffix format.

### 9.1 Suffix encoding

| Suffix | Meaning |
|--------|---------|
| (none) | Normal event |
| `[!]` | Important |
| `[C]` | Completed |
| `[!/C]` | Important **and** completed |

Examples:
```
"Buy groceries"        → normal event
"Buy groceries [!]"    → important, incomplete
"Buy groceries [C]"    → completed
"Buy groceries [!/C]"  → important and completed
```

### 9.2 `TaskifyHelper`

The stateless singleton `TaskifyHelper` handles all encoding/decoding:

```kotlin
// Decode a raw title into TaskMeta:
val meta: TaskMeta = TaskifyHelper.parseTitle(event.title, taskifyModeEnabled = true)
// meta.cleanTitle  → "Buy groceries"
// meta.isImportant → true/false
// meta.isCompleted → true/false

// Encode metadata back into a title:
val newTitle = TaskifyHelper.encodeTitle(meta.cleanTitle, meta.isImportant, newCompleted)

// Just strip the suffix for display:
val display = TaskifyHelper.getDisplayTitle(event.title)
```

### 9.3 Toggling completion

To toggle an event's completion from any view:
```kotlin
ensureBackgroundThread {
    val fresh = ctx.eventsDB.getEventWithId(event.id!!) ?: return@ensureBackgroundThread
    val meta = TaskifyHelper.parseTitle(fresh.title, taskifyModeEnabled = true)
    fresh.title = TaskifyHelper.encodeTitle(meta.cleanTitle, meta.isImportant, !meta.isCompleted)
    ctx.eventsHelper.updateEvent(fresh, updateAtCalDAV = true, showToasts = false)
}
```

### 9.4 UI integration checklist

When rendering events in a new view under Taskify Events Mode, apply these rules:

| Element | Rule |
|---------|------|
| Checkbox (`ic_checkbox_unchecked/checked_vector`) | Visible on right; hidden for real tasks and normal mode |
| Important icon (`ic_important_vector`) | Visible on left when `isImportant == true` |
| Strike-through on title | Apply when `isCompleted == true` |
| Red line overlay | Visible when `isCompleted == true` |
| Text colour | Dimmed (`adjustAlpha(MEDIUM_ALPHA)`) when `isCompleted == true` |

```kotlin
// Helper extension in TextView.kt:
titleTextView.checkViewStrikeThrough(isCompleted)
```

### 9.5 Views that support Taskify Events Mode

| View | Checkboxes | Important icon | Strike-through |
|------|-----------|----------------|----------------|
| Daily View (`DayEventsAdapter`) | ✅ right | ✅ left | ✅ |
| Event List (`EventListAdapter`) | ✅ right | ✅ left | ✅ |
| Weekly Grid (`WeeklyGridFragment`) | ✅ right | ✅ left | ✅ |
| Month View (`MonthView.kt`) | ❌ hidden | ✅ left | ✅ |
| Monthly Daily (`day_monthly_event_view`) | ❌ hidden | ✅ left | ✅ |

---

## 10. CalDAV Sync

### 10.1 Overview

CalDAV sync is implemented in `CalDAVHelper` using the Android `CalendarContract` system
ContentProvider. The flow is:

```
Config.caldavSync = true
  → scheduleCalDAVSync() in Context.kt sets up a repeating alarm
    → CalDAVSyncReceiver receives the broadcast
      → CalDAVHelper.refreshCalendars() fetches all synced calendars
        → per-calendar: fetches/inserts/updates/deletes events via EventsDao
```

### 10.2 Enabling sync for a calendar

1. User enables CalDAV sync in Settings → CalDAV Sync.
2. `ManageSyncedCalendarsDialog` shows available device calendars.
3. Selected calendar IDs are stored in `Config.caldavSyncedCalendarIds`.
4. A first sync fires immediately; subsequent syncs use the alarm schedule.

### 10.3 Event source tracking

CalDAV events have `source = "Caldav-<calendarId>"`. Local events have
`source = "simple-calendar"`. Imported ICS events use `"imported-ics"`. Birthday/anniversary
auto-imports use `"contact-birthday"` / `"contact-anniversary"`. Never hard-code these strings;
use the constants in `Constants.kt`.

---

## 11. Notifications & Reminders

Reminders are stored on each `Event` as up to three `(minutes, type)` pairs:

- `minutes = REMINDER_OFF (-1)` → disabled
- `type = REMINDER_NOTIFICATION` → system notification
- `type = REMINDER_EMAIL` → email (CalDAV only)

`Context.scheduleNextEventReminder()` sets up an `AlarmManager` exact alarm for the next
upcoming reminder of a given event. `NotificationReceiver` handles the alarm and posts a
notification via `NotificationManager`. The notification includes an action to snooze
(`SnoozeService`) or mark a task completed (`MarkCompletedService`).

---

## 12. Widgets

Three widget types are provided:

| Widget | Provider | Configure Activity |
|--------|----------|--------------------|
| Monthly calendar | `MyWidgetMonthlyProvider` | `WidgetMonthlyConfigureActivity` |
| Event list | `MyWidgetListProvider` | `WidgetListConfigureActivity` |
| Date display | `MyWidgetDateProvider` | `WidgetDateConfigureActivity` |

Widget configuration is persisted in the `widgets` Room table. `WidgetService` /
`WidgetServiceEmpty` serve the `RemoteViewsService` used by the list widget.

Call `context.updateWidgets()` (from `Context.kt`) whenever events change so all widgets
refresh automatically.

---

## 13. Key Design Patterns

### 13.1 Background threading

**Never** run database queries or network calls on the main thread. Use:

```kotlin
ensureBackgroundThread {
    // DB / network work here
    activity?.runOnUiThread {
        // UI updates here
    }
}
```

`ensureBackgroundThread` is provided by fossify-commons. It runs on a cached thread pool if
already off the main thread, or posts to a background thread if on main.

### 13.2 Context extension properties (DB accessors)

```kotlin
val Context.eventsDB: EventsDao     get() = EventsDatabase.getInstance(applicationContext).EventsDao()
val Context.eventsHelper: EventsHelper get() = EventsHelper(this)
val Context.config: Config          get() = Config.newInstance(applicationContext)
```

These are stateless (the database singleton itself is cached). Use them freely from
fragments and activities.

### 13.3 View binding

All UI uses **ViewBinding** (enabled in `buildFeatures { viewBinding = true }`). Never
use `findViewById`. Inflate with `XyzBinding.inflate(inflater, parent, false)`.

### 13.4 Day codes

A **day code** is a compact date string in `YYYYMMdd` format (e.g., `"20260321"`). It is used
as the primary key for scrollable view pages and as Bundle arguments.

```kotlin
Formatter.getTodayCode()                   // today's day code
Formatter.getDayCodeFromDateTime(dateTime) // from a Joda DateTime
Formatter.getDateTimeFromCode(dayCode)     // parse back to DateTime
```

### 13.5 Timestamps

Events use Unix timestamps in **seconds** (not milliseconds). Use the `seconds()` extension:

```kotlin
val ts: Long = DateTime.now().seconds()    // current time as epoch seconds
```

### 13.6 `MyFragmentHolder` contract

All view fragments implement the `MyFragmentHolder` abstract class. When adding a new view,
implement every abstract method even if some are no-ops (e.g., `printView() {}`).

---

## 14. How To: Add a New View Mode

Follow these steps to add a view mode equivalent to how `WEEKLY_GRID_VIEW` was added.

### Step 1 — Add the constant

In `helpers/Constants.kt`, add a new `const val`:
```kotlin
const val MY_NEW_VIEW = 9   // pick the next available integer
```

### Step 2 — Create the fragment

Create `fragments/MyNewFragment.kt` extending `MyFragmentHolder()`:

```kotlin
class MyNewFragment : MyFragmentHolder() {
    override val viewType = MY_NEW_VIEW

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, …): View {
        // inflate your layout
    }

    override fun goToToday() { /* scroll to today */ }
    override fun showGoToDateDialog() { /* show date picker */ }
    override fun refreshEvents() { /* reload from DB */ }
    override fun shouldGoToTodayBeVisible(): Boolean = true
    override fun getNewEventDayCode(): String = Formatter.getTodayCode()
    override fun printView() {}
    override fun getCurrentDate(): DateTime? = null

    companion object {
        fun newInstance(dayCode: String): MyNewFragment = MyNewFragment().apply {
            arguments = Bundle().apply { putString(DAY_CODE, dayCode) }
        }
    }
}
```

### Step 3 — Create the layout

Add `res/layout/fragment_my_new.xml`.

### Step 4 — Wire into MainActivity

In `MainActivity.kt`:

1. **Add to the import list.**
2. **Add to `showViewDialog()`:**
   ```kotlin
   RadioItem(MY_NEW_VIEW, getString(R.string.my_new_view)),
   ```
3. **Add to `getFragmentForViewType()`** (the `when` expression that returns a fragment):
   ```kotlin
   MY_NEW_VIEW -> MyNewFragment.newInstance(…)
   ```
4. **Add to `getDateCodeFormatForView()`** so the toolbar title formats correctly:
   ```kotlin
   MY_NEW_VIEW -> Formatter.getDayCodeFromDateTime(date)
   ```
5. **Add to the FAB visibility check** if the new view should hide the FAB:
   ```kotlin
   binding.calendarFab.beVisibleIf(view != YEARLY_VIEW && view != MY_NEW_VIEW)
   ```

### Step 5 — Add the string resource

In `res/values/strings.xml`:
```xml
<string name="my_new_view">My new view</string>
```

---

## 15. How To: Add a New Setting

### Step 1 — Add the preference key constant

```kotlin
// helpers/Constants.kt
const val MY_SETTING = "my_setting"
```

### Step 2 — Add the Config property

```kotlin
// helpers/Config.kt
var mySetting: Boolean
    get() = prefs.getBoolean(MY_SETTING, false)   // false = default value
    set(value) = prefs.edit().putBoolean(MY_SETTING, value).apply()
```

### Step 3 — Add the UI element

In `res/layout/activity_settings.xml` (or the relevant include), add a
`SettingsCheckBoxHolder` / `SettingsTextViewHolder` as appropriate.

### Step 4 — Wire in SettingsActivity

```kotlin
// In SettingsActivity.setupGeneralSection() (or the relevant section):
settingsMySetting.isChecked = config.mySetting
settingsMySettingHolder.setOnClickListener {
    settingsMySetting.toggle()
    config.mySetting = settingsMySetting.isChecked
}
```

### Step 5 — Add the string

```xml
<string name="my_setting">My setting description</string>
```

---

## 16. How To: Modify Event Rendering

Event rendering differs by view. The table below shows where to look:

| View | Layout | Adapter / Code |
|------|--------|----------------|
| Daily scroll list | `event_list_item.xml` | `DayEventsAdapter.kt` |
| Monthly daily list | `day_monthly_event_view.xml` | `Context.kt` (`getEventListItems`) |
| Agenda / Event list | `event_list_item.xml` | `EventListAdapter.kt` |
| Month grid (canvas) | N/A (drawn in Kotlin) | `MonthView.kt` |
| Week timeline | `weekly_view_day_column.xml` | `WeekFragment.kt` |
| Weekly grid | `fragment_weekly_grid.xml`, `weekly_grid_day_column.xml`, `weekly_grid_event_item.xml` | `WeeklyGridFragment.kt` |

### Common changes

**Change the number of title lines:**
```xml
<!-- In the relevant XML layout -->
<TextView android:id="@+id/event_item_title"
    android:maxLines="3"
    android:ellipsize="none" … />
```

**Change weekly grid row sizing / full-height behavior:**
1. Update `fragment_weekly_grid.xml` to adjust the four row containers (`layout_height="0dp"` + `layout_weight`).
2. Update `weekly_grid_day_column.xml` if the day column should stop filling the whole cell.
3. Keep `weekly_grid_day_events` weighted if you still want each cell to stretch to the bottom of the row.

**Add a new icon to every event row:**
1. Add the `ImageView` to the layout XML with `visibility="gone"`.
2. Show/hide it in the adapter's `bindView()` / `setupEventItem()` method based on your condition.
3. Ensure the title `TextView` has its `layout_constraintEnd_toStartOf` pointing to the new icon
   (so the title shrinks rather than overlapping).

**Apply a custom text style:**
```kotlin
// Use the extension from TextView.kt
myTextView.checkViewStrikeThrough(shouldStrikeThrough)
myTextView.applyTaskifyCompletedStyle(isCompleted, dimmedColor)
```
