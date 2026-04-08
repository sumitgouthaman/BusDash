# Agent's Guide to BusDash

Welcome to **BusDash**, a fast, glanceable transit dashboard for Android and Wear OS. This guide is designed to help you get up to speed with the project's architecture, technology stack, and core logic.

## Project Overview

BusDash is a real-time transit dashboard powered by the [OneBusAway API](https://onebusaway.org/). It is optimized for commuters who need quick access to arrival times for their regular stops.

### Key Features:
- **Nearby Arrivals**: Automatically detects nearby stops using location services.
- **Starred Stops**: Users can star stops for quick access on both phone and watch.
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
  - `AppPreferences.kt`: Manages user settings and starred stops using DataStore.
  - `LocationHelper.kt`: Encapsulates FusedLocationProviderClient logic.
  - `WearDataSync.kt`: Responsible for pushing configuration and starred stops to the Wear OS device.
- **`ui/`**: Compose-based UI.
  - `screens/`: `DashboardScreen` (main list), `SettingsScreen` (configuration), `StopDetailsScreen`.
  - `theme/`: App-specific Material 3 theme.

### 2. `wear` (Wear OS)
- **`data/`**: Wear-specific logic.
  - `WearPreferences.kt`: Stores API keys and settings received from the phone.
  - `WearConfigReceiver.kt`: A `WearableListenerService` that listens for data changes from the phone.
- **`ui/`**: Wear-optimized Compose UI.
  - `screens/`: `WearDashboardScreen` (optimized for round displays), `SetupPromptScreen`.

## Core Logic & Workflows

### Data Synchronization
The phone app acts as the primary configuration source. When the API key or starred stops change in `AppPreferences`, `WearDataSync` sends this data via the `DataClient` to the Wear OS device. The watch's `WearConfigReceiver` then updates its local `WearPreferences`.

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
