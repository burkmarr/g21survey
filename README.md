# g21 Survey Android

A native Android starter app that displays an EPSG:27700 raster basemap from the selected project folder.

Choose a project folder from **Settings**. Place the basemap JPEGs and their matching world files in its `basemap` subfolder.

Use the bottom navigation bar to switch between **Map**, **Survey**, **Records**, **Edit**, and **Settings**. The map opens at the view you last used (position and zoom), or fitted to the whole basemap on first run. Pan by dragging and zoom with a pinch gesture.

The **Records** page reads `shortcuts.csv` from the selected project folder. Its header row must include `code` and `survey` columns, with `survey` immediately after `code`. A second row with `tags` in the `code` column can mark fields as `date`, `time`, `gr`, `site`, `comment`, `scientific`, `vernacular`, or `quantity`; it is not shown as a recording template. For each template, the app fills a tagged field only when that template's value is blank. `date`, `time`, `gr`, and `site` are populated automatically; the other tags are currently left blank. In code, rows marked `yes` in the `survey` column are identified as survey taxa; the app currently treats them like other taxa.

A template with the code `#` opens a form to create a record and a reusable custom record button. Enter a reference code (unique within the current survey) and a button code; the new button is labeled `#<reference code> <button code>`. Its label is saved in the `scientific` field of both the initial record and later records created from that button. The creation comment is saved only with the initial record, is not inherited by later records, and is also retained as button context; use **Speak** to dictate the comment. Commented buttons display the full comment beneath the button. Custom button definitions and their context are stored with their initial records in app metadata, not in the records CSV. Custom buttons are removed when the survey ends; the `#` template remains available for the next survey. On the **Records** page, regular template buttons are laid out three per row; the `#` template buttons and custom buttons are full-width at the end of the list.

Each survey creates a matching `g21s-<timestamp>-records.csv` and `g21s-<timestamp>-track.gpx` in the project's `surveys/current` folder. The GPX file is created when the survey starts and updated whenever a record is added. When ending a survey, choose to delete both files or archive them in `surveys/previous`; archiving saves the final track first. If ending fails, use **End survey** again to retry. Tapping a template while surveying appends its values, replacing `#gr#` with the OS grid reference; existing inline `#date#`, `#time#`, and `#site#` tokens are also replaced. In **Settings**, choose 8- or 10-figure grid references (written without spaces), whether recorded times include seconds, and the current location name. A record can still be added without a location name, but the app warns you.

While tracking, template buttons are disabled at a GPS grid reference if a record with that template code and grid reference has already been added to the current records file.

The **Edit** page lists records from the current survey. Each card shows the code and the columns tagged `scientific`, `time`, `gr`, `quantity`, `site`, and `comment`. Edit a record to change its code, quantity, site, or comment, or delete it after confirmation. Integer quantities use a numeric keypad by default; select **Use text** to enter a word or other non-numeric value. Use **Speak** beside the comment field to append recognized speech, then review or edit the text before saving. Changing the code updates the `scientific` and `vernacular` fields from the selected template; other fields are preserved. Editing requires `quantity`, `site`, and `comment` tags, and changing the code also requires `scientific` and `vernacular` tags. A code cannot be changed to one already recorded at the same grid reference.

The map draws a clear black-bordered six-figure (100 m) grid square as soon as the survey track enters it. Each square shows the number of survey taxa recorded there, starting at zero; counts update when survey taxa are added, edited, or deleted. Records count toward the same square regardless of their 8- or 10-figure recording precision.

On the **Survey** screen, select any number of surveys from the project's `surveys/previous` folder to display their tracks and squares. Each entry shows the survey date and the location from its first record. Counts combine selected surveys with the current survey: record counts add together, while species counts count each survey-taxon code once per square. Previous records are matched to the current `shortcuts.csv` by the field tagged `scientific`; missing or ambiguous matches and invalid grid references are reported on the Survey screen and excluded from counts. Archived CSV column names must match the current scientific and grid-reference headers. Squares on a previous track are derived from its saved GPX points; record squares are also shown. Selections are remembered separately for each project. Use **Refresh previous surveys** to reload the archive list after changing files outside the app.

The records CSV excludes both the `code` and `survey` columns. Codes and their associated grid references are stored separately in app data on the device for the active survey, so clearing app data or moving the CSV to another device does not transfer those associations. If the CSV or its header is changed externally during a survey, the app reports the mismatch rather than overwriting it.

Your current GPS position is always shown as a blue marker, with a shaded circle for GPS accuracy. The map's recenter button flashes red while path tracking is active. The app asks for location permission on first launch, and device location must be on. GPS fixes are converted to EPSG:27700 using the full OSTN15 NTv2 grid transformation in `BritishNationalGrid.kt`; Android WGS84 fixes are treated as ETRS89 input.

For testing, enter an 8-figure British National Grid reference under **Settings > Testing** and enable **Use false origin**. The current GPS position (or the next fix if none is available) is mapped to the centre of that 10 m square; subsequent fixes retain the same grid offset, including in records and exported GPX tracks. The map flashes **False tracking** while enabled. The offset persists until the mode is turned off; disable it before changing the reference. Changing the mode during a survey can create a jump in the displayed and exported track, but the jump is excluded from the reported track length. Positions outside OSTN15 coverage cannot be converted and are reported as errors.

The bundled OSTN15 transformation data is licensed under the BSD License. Its required Ordnance Survey copyright notice and licence terms are included in `app/src/main/assets/OSTN15-LICENSE.txt`.

On the **Survey** screen, use **Start survey** and **End survey** to control GPS path recording. In **Settings**, adjust the **Movement threshold** from 1 to 20 metres (default 5) with the explicit **-** and **+** buttons. A point is added to the track only when it is farther than the threshold from the last recorded point; the live position marker still updates for every GPS fix. The summary shows both GPS fixes received and points recorded. Active recording uses the GPS provider at the shortest requested interval and a foreground service so it continues while the app is backgrounded or the screen is locked. Android may ask for notification permission so the ongoing tracking notification is visible. The in-memory track is cleared after the chosen end action succeeds. The track is drawn over the map, and its start time, length, elapsed time, and fix/point counts appear at the foot of the Survey screen.

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
| Run unit tests | `gradle test` | Runs local unit tests |
| Clean | `gradle clean` | Removes build output |

### Phone setup

1. Enable **Developer options** and **USB debugging** (or **Wireless debugging**) on the phone.
2. Connect it and run `adb devices`.
3. Accept the "Allow USB debugging?" prompt on the phone. The device must show as `device`, not `unauthorized`.
4. Run **Install on phone**, then **Launch on phone**.
