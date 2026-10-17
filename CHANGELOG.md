# Phosphor — independent Android implementation

## 1.1 — offline ascii.rest artwork

- Bundled all **217 pieces actually published in `ascii.rest` 0.4.0** under its MIT license. The live website's newer catalogue can differ from this pinned npm release.
- New **art** page and optional artwork instead of the weather landscape on **Zen**. Browse by category or search by name/description; selection, original-color/launcher-ink mode, pause state and Zen mode are saved locally.
- Uses the actual upstream renderer and glyph atlas in a strictly local, touch-through WebView scene slot. Launcher layout and controls remain Kotlin/Canvas. No website, CDN, remote scripts, account or additional permission is required. External navigation, network loads, file/content access and JavaScript interfaces are disabled.
- Whole-piece aspect ratio is retained, including square-cell colored scenes. Letterboxing matches the launcher background. Animations pause off-page/in the background and are capped at 12 FPS, or 4 FPS in Android battery saver, without restarting their play time on color/pause/FPS changes.
- Handles the system `uiMode` change without recreating this independently themed launcher: battery-saver dark-mode changes no longer reset artwork. Page edits also leave the temporary art preview correctly.
- Explicit renderer error/retry surface, recoverable WebView-renderer crashes, and the upstream attribution/license in the app.

**Use:** ◆ phosphor → **ASCII art** → **browse catalogue** / **open art page**. Toggle **Zen scene** to use the selection on Zen, or edit/add a page with type **art**. The art page has direct choose, pause/play and color controls. Android live wallpaper is unchanged by this option.

**Rebuild artwork assets:** Node 22.18+ is required only to regenerate the bundled assets, not to build/install the already complete Android project. Dependencies and renderer are pinned in `package-lock.json`.

```text
npm ci --ignore-scripts
npm run build:ascii
gradlew.bat :app:assembleDebug
```

`scripts/build-ascii.ts` generates the catalogue, bundled player, local HTML and original MIT license in `app/src/main/assets/ascii/`. Runtime never retrieves additional art. The upstream source is https://github.com/bas3line/ascii.

**Exercised:** APK 1.1 built and installed over the existing app on Android 16/API 36. All 217 pieces rendered their initial frame in both original-color and monochrome modes through the actual Android WebView player (434 configurations, no rendering errors). Inspected animated night coast, donut and Tux; canvas contents remained identical on pause and changed after play. Native selection, category/name search, swipe-through navigation, off-page frame suspension, saved selection after a force-stop/relaunch with airplane mode and Wi-Fi/data disabled, Zen, paper theme, and 720×1280 XL controls were checked. Measured approximately 12 FPS normally and 4 FPS in battery saver with the same renderer instance after the `uiMode` fix. An injected renderer crash displayed Retry, and retry restored artwork without changing the launcher PID. Main-process AndroidRuntime error output was empty after recovery. Screenshots are in `captures/ascii-*.png` (untracked).

## 1.0

Native Kotlin / Android Canvas launcher inspired by the public screens and feature descriptions at https://phosphor.zanoski.com/. Not the original paid application; no original APK, application code, fonts or packaged artwork is included. Pixel-for-pixel equivalence to the closed-source application is not claimed.

- Character-grid dashboard, block clock, seconds rail, CRT glow/scanlines, print-style clock shadows, animated ambience and touch ripple.
- Eighteen selectable page types, up to ten pages with add/remove/reorder; fourteen palettes; four text sizes, 12/24-hour time and temperature/wind units.
- Installed-app drawer with typo-tolerant search, app launch, web search, pins, app information and uninstall actions.
- Actual battery/memory/storage/uptime and network rates; quick actions use official Android panels for protected switches.
- Optional notification listener with open/dismiss and MediaSession transport controls; optional calendar agenda and busy-day markers.
- Open-Meteo city lookup / coarse-location forecast, local cache, animated weather landscape, hourly temperatures and precipitation, three-day forecast. Unavailable data is explicitly identified.
- Todo, persistent notes, BigDecimal calculator, configurable world clocks, sun/moon page, battery history, storage breakdown and device fetch.
- Persisted Pomodoro alarms, background phase changes, optional notifications/vibration, exact-alarm permission handling and boot restoration.
- Matching live wallpaper, optional clock/weather/battery/event/notification-count lines, separate palette and system-controlled lock-screen installation. Android's actual lock screen and notification overlays remain system-owned.

### Build and install

JDK 17 and Android SDK platform 35 are required. Set `JAVA_HOME` and `ANDROID_HOME`, or configure `sdk.dir` in an untracked `local.properties`.

```text
gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app declares Android 8.0/API 26 minimum and targets API 35. Open **◆ phosphor → set default launcher** to assign HOME. Optional access is under **settings → optional permissions**; configure weather separately. Long-press a blank region to edit pages, swipe sideways between pages, swipe up for apps, swipe down for the system shade. Hold an app for pin/info/uninstall.

For the reference-like lock-screen composition, select a small/compact **system** clock in Android's Wallpaper & style / Lock screen settings. The installation flow explains this: a large double-line Android clock can overlap live-wallpaper content, and third-party wallpaper cannot reposition it.

No account, ads or analytics. Local content stays on the phone. Configuring weather sends a city name to `geocoding-api.open-meteo.com` or coarse coordinates to `api.open-meteo.com`. Storage inaccessible to the app is labelled as such; media meters are visual animation, not audio-spectrum measurements. Lunar phase and golden-hour times are estimates. Exact timer delivery in doze requires Android's precise-alarm permission.

### Exercised verification

Debug APK built and installed on an isolated Android 16/API 36 emulator. Actual surfaces were inspected at 1080×2340 and 720×1280; evidence remains in `captures/` (untracked).

- HOME-role request and HOME-button routing; matching live-wallpaper preview, lock-screen binding and actual system lock screen.
- App search including `chorme` → Chrome, actual Chrome launch, long-press pinning, page add/remove/reorder, compact scrollable settings, print theme and XL layout.
- Saved todo completion and notes; calculator `0.1+0.2 = 0.3`, unary/bracket precedence `-(2+3)*4 = -20`, and visible division-by-zero feedback.
- One-minute Pomodoro switched to a five-minute break with the screen off and posted its phase notification.
- Disposable external Android fixture posted notifications, inserted a calendar event and supplied a MediaSession. Launcher showed the event, dismissed a notification, opened its real PendingIntent, and its pause control reached the fixture's MediaSession callback.
- Opening notifications exposed an Android 14+ PendingIntent sender opt-in requirement; fixed and verified. HOME-role requests use an activity-for-result flow. Reordering now retains the moved page as the selected page.
- Real geocoding responded; the forecast host timed out from both Android and the host environment. Live forecast acquisition therefore remains unverified. Weather/solar rendering and cached forecast parsing were separately exercised with explicitly labelled **TEST FIXTURE** data in the disposable emulator, then original preferences were restored. No fixture data or test application is packaged in the deliverable.

Physical devices, OEM-specific restrictions and pre-API-36 runtime behavior were not exercised.
