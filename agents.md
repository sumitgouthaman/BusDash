# Agent's Guide to BusDash

Welcome to **BusDash**, a fast, glanceable transit dashboard for Android and Wear OS. This guide is designed to help you get up to speed with the project's architecture, technology stack, and core logic.

## Project Overview

BusDash is a real-time transit dashboard powered by the [OneBusAway API](https://onebusaway.org/). It is optimized for commuters who need quick access to arrival times for their regular stops.

### Key Features:
- **Nearby Arrivals**: Automatically detects nearby stops using location services.
- **Starred Stops**: Users can star stops for quick access on both phone and watch.
- **Typical Commutes**: Users configure recurring bus notifications (stop + route + time + days). Alarms fire at the scheduled time, fetch live arrivals, and post a notification. Entries support full CRUD: add, edit (stop/route/time/days), toggle enabled, delete.
- **Wear OS Companion**: A dedicated Wear OS app that syncs configuration and starred stops from the phone.
- **Offline-First (Caching)**: Gracefully handles API rate limits and network issues with local caching.

## Tech Stack

- **Language**: [Kotlin](https://kotlinlang.org/)
- **UI Framework**: [Jetpack Compose](https://developer.android.com/compose) (Material 3)
- **Networking**: [Retrofit 3](https://square.github.io/retrofit/) with [Gson](https://github.com/google/gson)
- **Local Storage**: [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore)
- **Location Services**: [Google Play Services Location](https://developers.google.com/android/guides/setup)
- **Wearable Sync**: [Google Play Services Wearable](https://developers.google.com/android/guides/setup) (Data Client)
- **Concurrency**: [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & [Flow](https://kotlinlang.org/docs/flow.html)
- **Wear OS Layouts**: [Horologist Compose Layout](https://google.github.io/horologist/)

## Project Structure

The project is split into two main modules:

### 1. `app` (Android Phone)
- **`data/`**: Core business logic and data handling.
  - `OneBusAwayApi.kt`: Retrofit service interface.
  - `AppPreferences.kt`: Manages user settings, starred stops, and typical commutes using DataStore. Commutes are stored as a single JSON string (`typical_commutes` key) containing a `List<CommuteEntry>` serialised via Gson. All operations (add, update, remove, toggle) read the full list, mutate, and write the full list back within a `dataStore.edit {}` transaction.
  - `CommuteEntry.kt`: Data class for a typical commute (id, stopId, stopName, routeId, routeShortName, hour, minute, daysOfWeek, enabled). Includes `formattedTime()` and `formattedDays()` helpers, plus `toCommuteJson()` / `toCommuteList()` Gson extension functions.
  - `LocationHelper.kt`: Encapsulates FusedLocationProviderClient logic.
  - `WearDataSync.kt`: Responsible for pushing configuration and starred stops to the Wear OS device.
  - `PhoneWearableListenerService.kt`: Phone-side `WearableListenerService` that detects when the watch comes online and proactively pushes config.
- **`notifications/`**: Scheduled bus notification pipeline.
  - `CommuteAlarmScheduler.kt`: Schedules / cancels `AlarmManager` alarms for each `CommuteEntry`. Uses `entry.id.hashCode()` as the `PendingIntent` request code, so cancel and reschedule always target the same alarm slot.
  - `CommuteAlarmReceiver.kt`: Broadcast receiver that fires at alarm time; enqueues `CommuteNotificationWorker` and immediately reschedules the next occurrence.
  - `CommuteNotificationWorker.kt`: `CoroutineWorker` that fetches live arrivals for the commute's stop/route, formats the next 3 departure times, posts a local notification, and sends a Wear OS message.
- **`ui/`**: Compose-based UI.
  - `screens/`: `DashboardScreen` (main list), `SettingsScreen` (configuration), `StopDetailsScreen`, `CommuteListScreen` (list of typical commutes), `AddCommuteScreen` (add **and** edit commutes — controlled by the `editCommuteId` parameter).
  - `theme/`: App-specific Material 3 theme.

### 2. `wear` (Wear OS)
- **`data/`**: Wear-specific logic.
  - `WearPreferences.kt`: Stores API keys and settings received from the phone.
  - `WearConfigReceiver.kt`: A `WearableListenerService` that listens for data changes from the phone.
- **`ui/`**: Wear-optimized Compose UI.
  - `screens/`: `WearDashboardScreen` (optimized for round displays), `SetupPromptScreen`.

## Core Logic & Workflows

### Typical Commutes — Scheduling & Notification Pipeline

Each `CommuteEntry` has a stop, route, notification time (hour + minute), and a set of days of week (Calendar constants: 1=Sunday … 7=Saturday). The full lifecycle:

**Scheduling**: `CommuteAlarmScheduler.schedule()` computes the next trigger timestamp by starting from tomorrow at the configured time and advancing day-by-day until landing on an enabled day. It sets an exact alarm (`AlarmManager.setExactAndAllowWhileIdle` if permission granted, otherwise inexact). The request code is `entry.id.hashCode()` — stable across reschedules so that `cancel()` and `schedule()` always operate on the same PendingIntent slot.

**Alarm → notification**: `CommuteAlarmReceiver` fires, enqueues `CommuteNotificationWorker` (requires network), and immediately reschedules the *next* occurrence for the same entry. The worker fetches live arrivals, filters to the configured route and future departures, formats the top 3 times, posts a notification, and sends a Wear OS message.

**Stale guard**: If the alarm fires more than 30 minutes after the scheduled time (e.g. device woke late), the worker skips the notification.

**Edit flow**: When a commute is edited, the old alarm is cancelled (`CommuteAlarmScheduler.cancel(context, original)`) before `AppPreferences.updateCommute()` is called, then a new alarm is scheduled for the updated entry. The `enabled` state is preserved from the original entry via `original.copy(...)`.

**Boot persistence**: `BootReceiver` listens for `BOOT_COMPLETED` and calls `CommuteAlarmScheduler.rescheduleAll()` to restore all enabled commute alarms after a reboot.

**AddCommuteScreen dual mode**: The same screen handles both create and edit. Pass `editCommuteId = ""` (default) for add; pass the existing entry's UUID for edit. In edit mode the screen shows a loading spinner while `AddCommuteViewModel.loadForEdit()` fetches the entry and its stop's routes from the OBA API, then populates all form fields via a `LaunchedEffect(editingEntry?.id)`.

### Data Synchronization (Phone → Watch)

The phone pushes config to the watch over the **Wearable Data Layer** at path `/busdash-config`. The sync is one-directional: phone → watch only.

**Trigger**: `WearDataSync.startSync()` is called from a `LaunchedEffect(Unit)` inside the `BusDashApp` composable. It `combine`s all five preference flows — `apiKey`, `baseUrl`, `useMetricDistance`, `starredStops`, `starredRoutes` — and pushes a new `DataItem` to the watch whenever **any** of them changes.

**Payload fields** (`DataMap` keys):

| Key | Type | Notes |
|---|---|---|
| `api_key` | String | OBA API key |
| `base_url` | String | OBA server URL |
| `use_metric` | Boolean | Distance unit preference |
| `starred_stops` | StringArray | Set of stop IDs |
| `starred_routes` | StringArray | Compound keys `"stopId_routeId"` |
| `timestamp` | Long | Always included to force delivery; the Data Layer deduplicates identical `DataItem`s, so without this a re-push with unchanged data would be silently dropped |

The request is sent with `.setUrgent()` to prioritize delivery over battery savings.

**Watch side — push path**: `WearConfigReceiver` is a `WearableListenerService` registered in the manifest for action `DATA_CHANGED` with path prefix `/busdash-config`. It writes all received values atomically into `WearPreferences` (DataStore) via `updateFromPhone()`. `onDataChanged` only fires when a DataItem *changes after* the listener is registered — a freshly installed watch app will **not** receive DataItems that already existed before it was installed.

**Watch side — pull path**: `WearMainActivity.pullConfigFromDataLayer()` is called in `onCreate` and reads all existing DataItems from the local Data Layer replica using `DataClient.getDataItems()`. This is the most reliable mechanism for fresh installs and restarts: it does not require any push event and works even when the phone is offline, because the Data Layer is a local cache replicated from the phone.

**Scope lifetime**: `WearDataSync` owns a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that is never cancelled — it runs for the process lifetime. There is no mechanism to stop syncing once `startSync()` is called.

**On-demand push (watch connects while phone app is closed)**: `PhoneWearableListenerService` is a phone-side `WearableListenerService` that listens for `CAPABILITY_CHANGED`. The Wear app advertises the `busdash_wear` capability (declared in `wear/src/main/res/values/wear.xml`). When that capability appears on a connected node, `onCapabilityChanged` fires and the service immediately reads `AppPreferences` and pushes the full config snapshot, without the phone app needing to be open. Note: `onCapabilityChanged` only fires on state *transitions* (capability appears or disappears), not on the current state. To handle the case where the watch is already connected when the phone app opens, `WearDataSync.startSync()` also queries `CapabilityClient.getCapability()` at startup and pushes immediately if the watch is already reachable.

**Data Layer ownership**: The phone owns the single DataItem at `wear://<phoneNodeId>/busdash-config`. Every `putDataItem()` call overwrites the same entry — there is no per-installation or per-pairing accumulation. Uninstalling and reinstalling the watch app does not create additional entries; Play Services cleans up the watch-side replica on uninstall and re-syncs from the phone's single item on reinstall.

### Watch Data Fetching (Independent of Phone)

The Wear app fetches transit data **directly from the OBA API** — it does not relay requests through the phone. The `WearDashboardViewModel` builds its own Retrofit instance using the `baseUrl` it received from `WearPreferences`. This instance is created lazily on first load and cached in the ViewModel. **Important**: if `baseUrl` changes after first load, the cached Retrofit instance is not recreated; the old URL continues to be used until the ViewModel is destroyed.

### Watch Display Logic

`WearDashboardViewModel.loadData()` builds the display list as follows:

1. Starred stops (all that appear in nearby results), ordered by distance.
2. Then up to 3 nearest non-starred stops.

Both groups are always shown — starred stops are never used as an exclusive fallback.

Within each stop card, arrivals are **grouped by route** and sorted so that starred routes appear first, then by soonest departure. Up to 3 route groups are shown per card.

### Caching Strategy (Wear)

| Layer | TTL | Invalidation |
|---|---|---|
| Nearby stops | 2 minutes | Force-refresh or user moved >50 m |
| Arrivals per stop | 60 seconds | Each card's internal 60-second timer, or long-press on card |

On `loadData()`, if a valid stops cache exists and the user hasn't moved far, the cache is shown immediately and a background re-fetch is skipped (unless `force=true`).

Each `WearStopCard` runs an independent 1-second `LaunchedEffect` loop that updates the displayed time-since-fetch label and triggers an arrivals re-fetch every 60 seconds. A long-press on a card bypasses the cache and force-fetches arrivals for that stop only.

### Watch App Standalone Status

The Wear app declares `com.google.android.wearable.standalone = false`, meaning the watch app **requires** the paired phone app to be installed. Without the phone app pushing a config, `WearPreferences.isConfigured` remains `false` and the watch shows `SetupPromptScreen` instead of the dashboard.

### Starred Routes Key Format

Starred routes are stored as compound string keys in the format `"<stopId>_<routeId>"` (e.g., `"1_100223_100"`). This format is used in both `AppPreferences` and `WearPreferences`. When a stop is un-starred, all routes for that stop are automatically removed from `starredRoutes` (see `AppPreferences.toggleStarredStop`).

### API Key Requirement
The app requires a OneBusAway API key. During development, this is configured in the `SettingsScreen` on the phone.

### Wear OS Performance
**Critical**: Always test the `wear` module in the `release` build variant to ensure smooth scrolling performance, as Compose on Wear OS relies heavily on R8 optimizations.

## Getting Started

1.  **Sync Gradle**: The project uses Version Catalogs (`gradle/libs.versions.toml`).
2.  **Run Phone App**: Deploy the `app` module to an emulator or physical device.
3.  **Configure API Key**: Go to Settings in the phone app and enter a valid OneBusAway API key.
4.  **Run Wear App**: Deploy the `wear` module. If configured on the phone, the watch should sync automatically.

## Known Patterns & Conventions
- **Unidirectional Data Flow**: Screens observe state from `ViewModel`s (or directly from `Preferences` flows for simple state) and emit events.
- **Dependency Injection**: Currently uses simple manual injection/instantiation (e.g., `remember { AppPreferences(context) }` in Compose).
- **Error Handling**: API errors and rate limits should be handled gracefully by showing cached data or clear error messages.
- **CancellationException must be rethrown**: In coroutine `catch (e: Exception)` blocks, always add `catch (e: CancellationException) { throw e }` before the general handler. `CancellationException` is a subclass of `Exception`; swallowing it breaks structured concurrency and can surface cancellation as a visible UI error (e.g. "StandaloneCoroutine was cancelled" in an error state).
