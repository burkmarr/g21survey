# g21 Survey Android

A native Android starter app that displays the supplied EPSG:27700 raster basemap.

The basemap JPEGs and their world files are stored in `app/src/main/assets/basemap`.

Use the bottom navigation bar to switch between **Map** and **Tracking**. The map opens at the view you last used (position and zoom), or fitted to the whole basemap on first run. Pan by dragging and zoom with a pinch gesture.

Your current GPS position is always shown as a blue marker, with a shaded circle for GPS accuracy. The map's recenter button flashes red while path tracking is active. The app asks for location permission on first launch, and device location must be on. GPS fixes are converted from WGS84 to EPSG:27700 in `BritishNationalGrid.kt`.

On the **Tracking** screen, start or pause GPS path recording and set the **Movement threshold** from 1 to 20 metres (default 5). A point is added to the track only when it is farther than the threshold from the last recorded point; the live position marker still updates for every GPS fix. The summary shows both GPS fixes received and points recorded. Active recording uses the GPS provider at the shortest requested interval and a foreground service so it continues while the app is backgrounded or the screen is locked. Android may ask for notification permission so the ongoing tracking notification is visible. The current track is held in memory and is lost if Android terminates the app process. Starting again with a paused track lets you continue it or replace it. The track is drawn over the map, and its start time, length, elapsed time, and fix/point counts appear at the foot of the Tracking screen. A paused track can be saved as a WGS84 GPX file after choosing a destination with **Choose save folder**; the app asks you to select a folder if none is set. **Clear GPX** clears the in-memory track after confirmation and is only enabled while a track is paused. After saving, the app also offers to clear the track from memory.

## Development (VS Code only)

Android Studio is not needed. The project is built with the command-line Gradle and Android SDK tools, driven from VS Code.

### Prerequisites

- JDK 17 (`JAVA_HOME` set)
- Gradle 8.9 or later on `PATH` (the repo has no Gradle wrapper)
- Android SDK with platform 35 and platform-tools (`ANDROID_HOME` set, `adb` on `PATH`)
- VS Code extensions: *Kotlin* and *Gradle for Java* are helpful but optional

### Build, install and run

Run these from **Terminal > Run Task...** (or **Terminal > Run Build Task...**, `Ctrl+Shift+B`, for the build). The tasks are defined in `.vscode/tasks.json`.

| Task | Command | Purpose |
|------|---------|---------|
| Build debug APK | `gradle assembleDebug` | Output: `app/build/outputs/apk/debug/app-debug.apk` |
| Install on phone | `gradle installDebug` | Builds and installs on the connected device |
| Launch on phone | `adb shell am start -n org.g21survey.app/.MainActivity` | Starts the app |
| Run unit tests | `gradle test` | No tests exist yet |
| Clean | `gradle clean` | Removes build output |

### Phone setup

1. Enable **Developer options** and **USB debugging** (or **Wireless debugging**) on the phone.
2. Connect it and run `adb devices`.
3. Accept the "Allow USB debugging?" prompt on the phone. The device must show as `device`, not `unauthorized`.
4. Run **Install on phone**, then **Launch on phone**.
