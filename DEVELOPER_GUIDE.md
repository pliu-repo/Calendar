# Fossify Calendar – Developer Learning Guide

A complete, structured guide for developers onboarding to the Fossify Calendar Android
codebase. It covers architecture, data flow, UI rendering, extension points, and best
practices, with concrete examples drawn from the actual source code.

---

## Table of Contents

1. [High-Level Architecture](#1-high-level-architecture)
2. [Important Directories & Files](#2-important-directories--files)
3. [Data Flow](#3-data-flow)
4. [UI System](#4-ui-system)
5. [Event Model & Parsing](#5-event-model--parsing)
6. [Extending the Codebase](#6-extending-the-codebase)
7. [Building & Debugging](#7-building--debugging)
8. [Best Practices](#8-best-practices)

---

## 1. High-Level Architecture

### Overall Structure

Fossify Calendar is a native Android application written entirely in Kotlin. It follows a
**hybrid MVP-ish architecture** with no external dependency-injection or reactive-streams
framework (no Dagger, Hilt, RxJava, or Coroutines). Threading is handled by the
`ensureBackgroundThread {}` utility from Fossify Commons, and results are delivered back
to the UI via plain callbacks.

The main layers are:

| Layer | Location | Role |
|---|---|---|
| **Data** | `databases/`, `models/`, `interfaces/` | Room DB, DAOs, plain data classes |
| **Business logic** | `helpers/` | Event/CalDAV operations, calendar logic, formatting |
| **UI** | `activities/`, `fragments/`, `adapters/`, `views/` | Screen logic and custom rendering |
| **Infrastructure** | `extensions/`, `receivers/`, `services/`, `jobs/` | DI shims, background work, widgets |

### Calendar Views

The app ships five calendar view modes, each implemented as a Fragment hierarchy:

```
MainActivity
├── MonthFragmentsHolder  (ViewPager)  →  MonthFragment  →  MonthView (custom Canvas view)
├── WeekFragmentsHolder   (ViewPager)  →  WeekFragment   →  WeeklyViewGrid
├── DayFragmentsHolder    (ViewPager)  →  DayFragment    →  DayEventsAdapter (RecyclerView)
├── MonthDayFragmentsHolder            →  MonthDayFragment
├── YearFragmentsHolder                →  YearFragment
└── EventListFragment                  →  EventListAdapter (RecyclerView)
```

Each `*FragmentsHolder` class extends `MyFragmentHolder` and manages a `ViewPager2` that
creates adjacent Fragments for swipe navigation. The concrete Fragment (e.g., `MonthFragment`)
owns one screen of content and talks to the business-logic layer through helper callbacks.

### How Events Are Loaded from the Android Calendar Provider

Fossify Calendar maintains its **own local Room database** as the authoritative store.
External calendar data (Google Calendar, Exchange, etc.) is pulled from the Android system
`CalendarContract` Content Provider via `CalDAVHelper` and written into Room. This means
the UI always reads from Room; CalDAV sync is a background refresh step.

The sync path is:

```
AppStartupWorker (WorkManager, one-shot on launch)
  └─ CalDAVHelper.refreshCalendars()
        └─ Queries CalendarContract.Calendars (Content Provider)
        └─ fetchCalDAVCalendarEvents(calendar, localCalendarId)
              └─ Queries CalendarContract.Events (Content Provider)
              └─ Maps columns → Event objects
              └─ Writes to Room via eventsDB
              └─ Calls context.updateWidgets()
```

### Adapters and View Holders

Adapters extend `MyRecyclerViewAdapter` (from Fossify Commons), which already handles
selection state, context-action-bar setup, and common operations. Each adapter can declare
multiple view-holder types (`getItemViewType`) and inflates the corresponding XML layout.
View holders are simple inner classes that hold binding references.

```kotlin
// Example from EventListAdapter.kt
class EventListAdapter(...) : MyRecyclerViewAdapter(...) {

    override fun getItemViewType(position: Int) = when (listItems[position]) {
        is ListEvent        -> ITEM_EVENT
        is ListSectionDay   -> ITEM_SECTION_DAY
        else                -> ITEM_SECTION_MONTH
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
        ITEM_EVENT        -> createViewHolder(R.layout.event_list_item, parent)
        ITEM_SECTION_DAY  -> createViewHolder(R.layout.event_list_section_day, parent)
        else              -> createViewHolder(R.layout.event_list_section_month, parent)
    }
}
```

---

## 2. Important Directories & Files

### Directory Map

```
app/src/main/kotlin/org/fossify/calendar/
├── activities/          # 11 Activity classes (entry points & screens)
├── adapters/            # 18 Adapter classes (RecyclerView & ViewPager)
├── databases/           # Room database singleton + migrations
├── dialogs/             # 19 Dialog classes
├── extensions/          # Kotlin extension functions (DI shims, utilities)
├── fragments/           # 12 Fragment classes (one per calendar view)
├── helpers/             # Business logic helpers, formatters, parsers
├── interfaces/          # DAOs + callback interfaces
├── jobs/                # WorkManager workers
├── models/              # 25 plain data classes / Room entities
├── objects/             # Singleton state objects
├── receivers/           # BroadcastReceivers (alarms, sync, widgets)
├── services/            # Foreground/background services
└── views/               # Custom View subclasses
```

### Key Files

#### `activities/MainActivity.kt` (~1,600 lines)

Entry point. Owns the bottom navigation bar, the top toolbar, and the `ViewPager`
container that holds the current `*FragmentsHolder`. Handles deep-link intents (e.g., an
alarm notification that should open a specific event), menu actions, and permission
requests.

#### `activities/EventActivity.kt` (~2,430 lines)

The event editor. Contains all form fields: title, time pickers, recurrence dialog,
reminder pickers, attendees, location, description, calendar selector, and color picker.
Calls `eventsHelper.insertEvent()` or `eventsHelper.updateEvent()` on save.

#### `fragments/MonthFragment.kt`

Implements `MonthlyCalendar` callback interface. Calls `MonthlyCalendarImpl.updateMonthlyCalendar()`
on background thread; receives the `ArrayList<DayMonthly>` result and passes it to
`MonthView.updateEvents()`.

#### `fragments/WeekFragment.kt` (~1,093 lines)

The most complex fragment. Manages an hour-grid layout (`WeeklyViewGrid`) overlaid with
positioned event views. Handles drag-to-reschedule, pinch zoom, and time-indicator
scrolling.

#### `fragments/EventListFragment.kt`

Shows all events in list format (Agenda view). Implements `RefreshRecyclerViewListener`.
Populates `EventListAdapter` with a mixed list of `ListEvent`, `ListSectionDay`, and
`ListSectionMonth` items.

#### `views/MonthView.kt` (~517 lines)

A fully custom `View` that renders the monthly calendar grid using `Canvas`. Holds 42
`DayMonthly` objects (six weeks). Key paints:

| Paint | Draws |
|---|---|
| `textPaint` | Day numbers |
| `eventTitlePaint` | Event text inside a day cell |
| `gridPaint` | Grid lines |
| `eventDotPaint` | Dot indicators for overflow events |
| `circleStrokePaint` | Ring around today or selected day |

#### `views/WeeklyViewGrid.kt`

Inflates a grid of time-slot columns, one per visible day. Event `View` objects are
programmatically positioned with `ConstraintLayout` parameters matching their start/end
timestamps.

#### `adapters/EventListAdapter.kt` (~258 lines)

Agenda-list adapter. Three view types (`ITEM_EVENT`, `ITEM_SECTION_DAY`,
`ITEM_SECTION_MONTH`). Supports selection (long-press), task completion toggle
(strike-through + dim), print mode, and auto-scroll to the first future section on init.

#### `adapters/DayEventsAdapter.kt` (~195 lines)

Single-day list adapter. Simpler than `EventListAdapter` – no section headers, no month
separators. Used inside `DayFragment` and `MonthDayFragment`.

#### `helpers/EventsHelper.kt` (~807 lines)

Central business-logic layer for all event CRUD. All public methods are async
(`ensureBackgroundThread { ... }`) and deliver results via callbacks on the UI thread.
Key methods:

```kotlin
fun getEvents(fromTS, toTS, eventId, applyTypeFilter, searchQuery, callback)
fun insertEvent(event, addToCalDAV, showToasts, callback)
fun updateEvent(event, updateAtCalDAV, showToasts, callback)
fun deleteEvents(eventIds, deleteFromCalDAV, callback)
```

#### `helpers/CalDAVHelper.kt` (~711 lines)

All interaction with `android.provider.CalendarContract`. Reads remote calendars and
events, maps them to local `Event` / `CalendarEntity` objects, and keeps them in sync.

#### `helpers/Parser.kt`

Converts iCalendar `RRULE` strings to/from `EventRepetition` objects. Also parses
`DTSTART`/`DTEND`/`EXDATE` timestamps.

#### `helpers/Config.kt` (~335 lines)

Typed wrapper around `SharedPreferences`. Every user-facing toggle has a property with a
getter and setter:

```kotlin
var showWeekNumbers: Boolean
    get() = prefs.getBoolean(WEEK_NUMBERS, false)
    set(value) = prefs.edit().putBoolean(WEEK_NUMBERS, value).apply()
```

#### `helpers/Formatter.kt`

Date/time formatting utilities used everywhere in the UI. Wraps Joda-Time's
`DateTimeFormatter`. Never instantiate `DateTimeFormatter` inline; always go through
`Formatter`.

#### `helpers/Constants.kt` (~343 lines)

All global constant values: repeat intervals (`DAY`, `WEEK`, `MONTH`, `YEAR` in seconds),
bit-flag values, source strings (`CALDAV`, `SOURCE_SIMPLE_CALENDAR`), intent extras, etc.

#### `databases/EventsDatabase.kt` (~187 lines)

Room database singleton (version 11). Four entities and four DAOs:

```kotlin
@Database(entities = [Event::class, CalendarEntity::class, Widget::class, Task::class], version = 11)
abstract class EventsDatabase : RoomDatabase() {
    abstract fun EventsDao(): EventsDao
    abstract fun CalendarsDao(): CalendarsDao
    abstract fun WidgetsDao(): WidgetsDao
    abstract fun TasksDao(): TasksDao
}
```

#### `extensions/Context.kt` (~1 208 lines)

The DI shim layer. Extension properties provide access to every major singleton without
passing them as constructor arguments:

```kotlin
val Context.config: Config          get() = Config.newInstance(applicationContext)
val Context.eventsDB: EventsDao     get() = EventsDatabase.getInstance(applicationContext).EventsDao()
val Context.calendarsDB: CalendarsDao ...
val Context.eventsHelper: EventsHelper get() = EventsHelper(this)
val Context.calDAVHelper: CalDAVHelper get() = CalDAVHelper(this)
```

This file also contains notification helpers, reminder scheduling, widget refresh, and
printing utilities.

---

## 3. Data Flow

### Reading Events (Provider → Room → UI)

```
┌─────────────────────────────────────────────────────────────────┐
│ Android CalendarContract Content Provider                       │
│  (system calendars, Google, Exchange via CalDAV)                │
└───────────────────┬─────────────────────────────────────────────┘
                    │  CalDAVHelper.refreshCalendars()
                    │  (runs in background via AppStartupWorker)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│ Room Database  (events.db)                                      │
│  EventsDao  ·  CalendarsDao  ·  TasksDao  ·  WidgetsDao         │
└───────────────────┬─────────────────────────────────────────────┘
                    │  EventsHelper.getEvents(fromTS, toTS, callback)
                    │  (ensureBackgroundThread → callback on UI thread)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│ Helper / Impl layer                                              │
│  MonthlyCalendarImpl · WeeklyCalendarImpl · YearlyCalendarImpl  │
│  Each fetches + filters, then calls the Fragment callback       │
└───────────────────┬─────────────────────────────────────────────┘
                    │  callback (ArrayList<Event> or DayMonthly list)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│ Fragment / Activity                                              │
│  Sets data on Adapter or custom View                            │
└───────────────────┬─────────────────────────────────────────────┘
                    │  adapter.updateListItems() / view.updateEvents()
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│ RecyclerView / Canvas View renders on screen                    │
└─────────────────────────────────────────────────────────────────┘
```

**Concrete example – Agenda view:**

```kotlin
// EventListFragment.kt
private fun refreshEvents() {
    val fromTS = System.currentTimeMillis() / 1000 - config.displayPastEvents * 60
    context.eventsHelper.getEvents(fromTS, Long.MAX_VALUE) { events ->
        // back on UI thread
        val listItems = buildSectionedList(events)   // adds ListSectionDay/Month headers
        runOnUiThread { eventListAdapter.updateListItems(listItems) }
    }
}
```

### Writing Events (UI → Room → Provider)

```
User fills EventActivity form
  └─ EventActivity.saveEvent()
        ├─ Validates fields (title required, end ≥ start, etc.)
        ├─ Builds Event object from form state
        └─ eventsHelper.insertEvent(event, addToCalDAV = true) {
              ├─ eventsDB.insertEvent(event)          (Room write)
              ├─ scheduleNextEventReminder(event)      (AlarmManager)
              ├─ calDAVHelper.insertCalDAVEvent(event) (if CalDAV calendar)
              └─ context.updateWidgets()               (widget refresh)
           }
```

The same pattern applies to `updateEvent()` and `deleteEvents()`.

---

## 4. UI System

### RecyclerViews

Three distinct RecyclerView roles exist in the app:

| Adapter | Used In | Item Types |
|---|---|---|
| `EventListAdapter` | `EventListFragment` (Agenda) | `ListEvent`, `ListSectionDay`, `ListSectionMonth` |
| `DayEventsAdapter` | `DayFragment`, `MonthDayFragment` | `Event` |
| `ManageCalendarsAdapter`, `FilterCalendarAdapter`, … | Settings & filter sheets | Single type each |

All adapters extend `MyRecyclerViewAdapter` from Fossify Commons. This base class wires up:

- Long-press multi-selection with checkboxes
- Context Action Bar (CAB) for bulk Delete / Share
- `DiffUtil`-based updates via `updateListItems()`

**Fragment setup pattern:**

```kotlin
// DayFragment.kt
binding.dayEvents.apply {
    layoutManager = LinearLayoutManager(context)
    adapter = DayEventsAdapter(
        activity = requireActivity() as SimpleActivity,
        events   = dayEvents,
        recyclerView = this,
        dayCode  = dayCode,
        itemClick = { event -> openEventEditor(event as Event) }
    )
}
```

### Custom Canvas Views

**MonthView** draws everything on a `Canvas` in `onDraw()`:

1. Draws the 7-column × 6-row grid (`drawGridLine`).
2. Iterates `days` (42 × `DayMonthly`) and draws day numbers.
3. For each day, iterates its `events` list and draws either full-text event bars (when
   space allows) or colored dots, plus a `+N` overflow indicator.
4. Draws a circle around the selected/today day.

Because all geometry is calculated in `onSizeChanged()` and cached in member variables,
`onDraw()` only issues `Canvas` draw calls – no object allocation.

**WeeklyViewGrid** takes a different approach: it inflates individual XML event-view
instances (`day_monthly_event_view.xml`) and positions them programmatically inside a
`ConstraintLayout`, using pixel heights derived from the hour-slot height and the event's
duration.

### XML Layouts → Kotlin Binding

View Binding is enabled (`buildFeatures { viewBinding = true }`). Every layout file
generates a binding class:

```kotlin
// In a Fragment
private var _binding: FragmentEventListBinding? = null
private val binding get() = _binding!!

override fun onCreateView(...): View {
    _binding = FragmentEventListBinding.inflate(inflater, container, false)
    return binding.root
}

override fun onDestroyView() {
    _binding = null  // avoid memory leaks
    super.onDestroyView()
}
```

Layouts live in `app/src/main/res/layout/` and follow naming conventions:

| Prefix | Purpose |
|---|---|
| `activity_` | Activity root layouts |
| `fragment_` | Fragment root layouts |
| `event_list_` | RecyclerView item layouts |
| `dialog_` | AlertDialog content layouts |
| `widget_` | AppWidget layouts |

---

## 5. Event Model & Parsing

### The `Event` Data Class

`Event` is a Room `@Entity` and the single unified model for both calendar events and
tasks. Tasks are distinguished by `type = TYPE_TASK`.

```kotlin
@Entity(tableName = "events", indices = [(Index(value = ["id"], unique = true))])
data class Event(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "start_ts")   var startTS:  Long = 0L,
    @ColumnInfo(name = "end_ts")     var endTS:    Long = 0L,
    @ColumnInfo(name = "title")      var title:    String = "",
    @ColumnInfo(name = "location")   var location: String = "",
    @ColumnInfo(name = "description")var description: String = "",
    // --- Reminders ---
    var reminder1Minutes: Int = REMINDER_OFF,  // -1 = off
    var reminder2Minutes: Int = REMINDER_OFF,
    var reminder3Minutes: Int = REMINDER_OFF,
    var reminder1Type: Int = REMINDER_NOTIFICATION,
    // --- Recurrence ---
    var repeatInterval: Int = 0,        // seconds between occurrences
    var repeatRule: Int = 0,            // REPEAT_SAME_DAY / REPEAT_ORDER_WEEKDAY / day bits
    var repeatLimit: Long = 0L,         // 0 = forever, >0 = end date, <0 = count
    var repetitionExceptions: List<String> = emptyList(),
    // --- Misc ---
    var flags: Int = 0,                 // bitwise: FLAG_ALL_DAY, FLAG_IS_IN_PAST, FLAG_TASK_COMPLETED
    var calendarId: Long = LOCAL_CALENDAR_ID,
    var source: String = SOURCE_SIMPLE_CALENDAR,
    var type: Int = TYPE_EVENT,         // TYPE_EVENT or TYPE_TASK
    var color: Int = 0,                 // 0 = use calendar color
    // … more fields
)
```

**Convenience helpers on `Event`:**

```kotlin
fun getIsAllDay()          = flags and FLAG_ALL_DAY != 0
fun getIsTaskCompleted()   = flags and FLAG_TASK_COMPLETED != 0
fun getReminders()         = listOf(reminder1Minutes, reminder2Minutes, reminder3Minutes)
                             .filter { it != REMINDER_OFF }
fun getCalDAVEventId()     = importId.substringAfterLast("-").toLongOrNull() ?: 0L
fun updateIsPastEvent()    // sets FLAG_IS_IN_PAST based on current time
```

### Reminder Handling

Each event stores up to three independent reminders. A reminder is a pair of
`(minutes, type)`:

- `minutes = REMINDER_OFF (-1)` → reminder slot disabled.
- `minutes = 0` → fire at event start.
- `minutes > 0` → fire N minutes before event start.
- `type` → `REMINDER_NOTIFICATION` or `REMINDER_EMAIL` (CalDAV only).

After an event is saved, `scheduleNextEventReminder(event)` (in `Context.kt`) computes the
next alarm timestamp and registers a `PendingIntent` with `AlarmManager`. The
`NotificationReceiver` BroadcastReceiver fires when that intent triggers.

### Recurrence (`repeatInterval`, `repeatRule`, `repeatLimit`)

The `Parser` class converts iCalendar RRULE strings bi-directionally:

```kotlin
// Parsing RRULE → EventRepetition
val repetition: EventRepetition = Parser().parseRepeatInterval(
    fullString = "FREQ=WEEKLY;BYDAY=MO,WE,FR",
    startTS    = event.startTS
)
// repetition.repeatInterval = WEEK_SECONDS
// repetition.repeatRule     = MONDAY_BIT or WEDNESDAY_BIT or FRIDAY_BIT

// Serialising back to RRULE
val rrule = Parser().getRepeatCode(event)
// → "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE,FR"
```

`Event.addIntervalTime(original)` advances a single occurrence to the next date, handling
edge cases like month-end clamping, last weekday of month, and cross-timezone DST shifts.

### Task-Like Title Prefixes

When a title string starts with specific prefixes, the app treats the event as a task with
extra semantics:

| Prefix | Meaning |
|---|---|
| `[]` | Uncompleted task |
| `[c]` | Completed task |
| `!` | High-priority / important event |

These prefixes are parsed in the adapter and view layers. In `EventListAdapter`,
`setupListEvent()` checks the `type` and `flags` fields, applies a strike-through span for
completed tasks, and shows the task-checkbox icon (`event_item_task_image`).

---

## 6. Extending the Codebase

### Adding a New UI Feature

1. **New screen?** Create an `Activity` in `activities/` extending `SimpleActivity`
   (which sets the status-bar color and theme from Fossify Commons).
2. **New calendar view mode?** Follow the existing Fragment pattern:
   - Add a `*Fragment.kt` in `fragments/`.
   - Add a `*FragmentsHolder.kt` to wrap it in a `ViewPager`.
   - Create an entry in the view-mode enum / constants in `Constants.kt`.
   - Register it in `MainActivity` navigation handling.
3. **New dialog?** Create a class in `dialogs/` that inflates a layout and returns a
   result via a lambda.

### Modifying Event Rendering

**In RecyclerView (list/day views):**

- Open the relevant adapter (`EventListAdapter.kt`, `DayEventsAdapter.kt`).
- Modify the `setupListEvent()` (or equivalent) `bind` call.
- The corresponding XML file (e.g., `event_list_item.xml`) provides the view hierarchy.

**In the month view (Canvas):**

- Open `MonthView.kt`.
- `setupMonthViewEvents()` converts raw `Event` objects to `MonthViewEvent` display
  objects (positioning, color, truncation).
- `onDraw()` → `drawDayEvents()` is where the Canvas calls happen.
- If you need a new visual element (e.g., an icon), add a new `Paint` field and issue the
  draw call inside `drawDayEvents()` after the existing event text draw.

**In the week view:**

- `WeekFragment.kt` calls `Context.getEventListItems()` and populates `WeeklyViewGrid`.
- Event view inflation happens via `DayMonthlyEventViewBinding` (bound to
  `day_monthly_event_view.xml`). Add new fields to that layout and the binding code in
  `WeekFragment`.

### Adding New Fields or Metadata to Events

1. Add the new column to `Event.kt`:
   ```kotlin
   @ColumnInfo(name = "my_new_field") var myNewField: String = ""
   ```
2. Increment `EventsDatabase.VERSION` by one.
3. Add a `MIGRATION_N_N+1` object that runs an `ALTER TABLE events ADD COLUMN` SQL:
   ```kotlin
   val MIGRATION_11_12 = object : Migration(11, 12) {
       override fun migrate(db: SupportSQLiteDatabase) {
           db.execSQL("ALTER TABLE events ADD COLUMN my_new_field TEXT NOT NULL DEFAULT ''")
       }
   }
   ```
4. Register the migration in `EventsDatabase.getInstance()`:
   ```kotlin
   .addMigrations(..., MIGRATION_11_12)
   ```
5. Update `CalDAVHelper` if the field should be synced with the Calendar Provider.
6. Update `IcsImporter` / `IcsExporter` if the field should survive ICS round-trips.

### Adding New Settings or Toggles

1. Add a constant key in `Constants.kt`:
   ```kotlin
   const val MY_FEATURE_ENABLED = "my_feature_enabled"
   ```
2. Add a typed property to `Config.kt`:
   ```kotlin
   var myFeatureEnabled: Boolean
       get() = prefs.getBoolean(MY_FEATURE_ENABLED, false)
       set(value) = prefs.edit().putBoolean(MY_FEATURE_ENABLED, value).apply()
   ```
3. Add a `SwitchPreferenceCompat` (or a custom preference row) in
   `activity_settings.xml`.
4. Wire the preference to the `Config` property in `SettingsActivity.kt`:
   ```kotlin
   binding.settingsMyFeature.isChecked = config.myFeatureEnabled
   binding.settingsMyFeature.setOnCheckedChangeListener { _, isChecked ->
       config.myFeatureEnabled = isChecked
   }
   ```
5. Reference `context.config.myFeatureEnabled` wherever the feature is needed.

---

## 7. Building & Debugging

### Running the App Locally

**Requirements:**
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 35

**Steps:**

```bash
# Clone the repository
git clone https://github.com/FossifyOrg/Calendar.git
cd Calendar

# Build a debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Or open in Android Studio and press ▶ Run
```

The project has three product flavors (`core`, `foss`, `gplay`). For local development
use the `fossDebug` variant to avoid Google-Play-specific dependencies.

### Inspecting Calendar Provider Data

Use `adb shell` to query the Content Provider directly:

```bash
# List all calendars visible on the device
adb shell content query --uri content://com.android.calendar/calendars \
    --projection _id,calendar_displayName,ownerAccount

# List events in calendar with ID 3
adb shell content query --uri content://com.android.calendar/events \
    --where "calendar_id=3" \
    --projection _id,title,dtstart,dtend,rrule
```

Or use the **Device File Explorer** in Android Studio to browse the app's local database:

```
/data/data/org.fossify.calendar/databases/events.db
```

Open it with any SQLite browser to inspect events, calendars, and migrations.

### Debugging Event Rendering Issues

**RecyclerView items not appearing:**

1. Add a `Log.d` call inside `onBindViewHolder` / `setupListEvent()` to confirm the
   adapter is receiving the expected list.
2. Check `EventListAdapter.getItemCount()` via the debugger.
3. Confirm `eventsHelper.getEvents()` callback is invoked on the UI thread and that
   `adapter.updateListItems()` is called afterwards.

**Month/week view not showing events:**

1. Breakpoint `MonthView.updateEvents()` – verify `allEvents` is non-empty.
2. Breakpoint `MonthView.onDraw()` / `drawDayEvents()` – verify `days[i].events` is
   populated.
3. Check that the calendar ID of the event is included in `config.displayCalendars`.

**CalDAV events not syncing:**

1. Verify the device has the system calendar app installed and accounts configured.
2. Check logcat for `CalDAVHelper` tag: `adb logcat -s CalDAVHelper`.
3. Trigger a manual sync from the overflow menu (⋮ → Sync now) and watch the logs.
4. Confirm `PERMISSION_READ_CALENDAR` and `PERMISSION_WRITE_CALENDAR` are granted.

**Reminder alarms not firing:**

1. Check `adb shell dumpsys alarm | grep fossify` to confirm PendingIntents are scheduled.
2. Look for `NotificationReceiver` in logcat.
3. On Android 12+, confirm the `SCHEDULE_EXACT_ALARM` permission is granted.

---

## 8. Best Practices

### Coding Style Conventions

Fossify Calendar follows standard Kotlin idioms without a formal style guide document.
The key conventions observed throughout the codebase:

- **Trailing lambda syntax** for all single-argument callbacks:
  ```kotlin
  eventsHelper.getEvents(fromTS, toTS) { events -> ... }
  ```
- **Extension functions** instead of utility classes with static methods:
  ```kotlin
  fun Context.scheduleNextEventReminder(event: Event) { ... }
  ```
- **Named arguments** for functions with more than two parameters.
- **`apply {}` / `also {}`** for object configuration instead of multiple assignments.
- **No `!!` null assertions** except in View Binding (`_binding!!`) where nullability is
  managed explicitly with `onDestroyView`.
- **Constants** in `Constants.kt`, never as string/int literals inline.

### Following Architectural Patterns

- **Never access the database on the main thread.** Always wrap DAO calls in
  `ensureBackgroundThread {}` and deliver results via `activity.runOnUiThread {}` or the
  fragment's `requireActivity().runOnUiThread {}`.
- **Access dependencies through `Context` extension properties**, not by constructing
  helpers directly: `context.eventsHelper`, `context.config`, `context.eventsDB`.
- **Business logic stays in `helpers/`**, not in Fragments or Activities. Fragments should
  only orchestrate: call a helper, receive a callback, update the adapter.
- **Keep adapters thin.** Adapters bind data to views; they do not issue network or
  database calls.
- **Use `Config` for all persistent state**, not `onSaveInstanceState` (which is for
  transient UI state only).

### Avoiding Regressions When Modifying Shared Components

**`EventsHelper.getEvents()`** is called from at least five fragments. Any change to its
filtering, sorting, or expansion of recurring events will affect all views simultaneously.
Test month, week, day, agenda, and widget after touching this method.

**`Event.addIntervalTime()`** drives recurrence expansion. A subtle bug here will produce
wrong dates for all repeating events. Verify edge cases: last day of month, DST transitions,
events with BYDAY weekly rules, and events with a COUNT-based repeat limit.

**`Context.kt` extension properties** are used everywhere. Adding a new property is safe;
modifying an existing one (especially `eventsDB` or `config`) affects the entire app.

**Database migrations** must be additive. Never rename or drop a column in a migration
without a corresponding code change. Add schema snapshots to `app/schemas/` by running:

```bash
./gradlew kaptFossDebugKotlin
# Schema JSON files are written to app/schemas/
```

Commit the updated schema file alongside your migration code so CI can verify schema
consistency.

**Widgets** read from the same Room database as the main app. After any schema change,
run the widget provider classes (`MyWidgetListProvider`, `MyWidgetMonthlyProvider`,
`MyWidgetDateProvider`) through a quick manual test on a home-screen widget instance.
