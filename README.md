# g21 Survey Android

A native Android starter app that displays the supplied EPSG:27700 raster basemap. The map opens fitted to all tiles, can be panned by dragging, zoomed with a pinch gesture, and refitted with the **Fit map** button.

The basemap JPEGs and their world files are stored in `app/src/main/assets/basemap`.

The **GPS** toggle in the header shows your current position on the map as a blue marker, with a shaded circle for GPS accuracy. The first time it is switched on, Android asks for location permission. Device location must be on. GPS fixes are converted from WGS84 to EPSG:27700 in `BritishNationalGrid.kt`.

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
