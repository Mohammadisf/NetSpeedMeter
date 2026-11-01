# NetSpeed Meter (Android)

A battery-friendly, **fully offline** Android data-usage + speed meter modelled
on the behaviour of the "Internet Speed Meter Lite" app: a live 1-second speed notification split by network type, and
a daily Mobile/WiFi usage history table. No ads, no analytics, no network
requests — the app never opens an internet connection (the `INTERNET`
permission is not declared).

## Features

- **Real-time speed** — reads the kernel byte counters (`TrafficStats`) every
  second, exactly like the reference app's ticker, and splits traffic into
  **Mobile** vs **WiFi** using the mobile-interface counters.
- **Live status-bar speed icon** — the notification's small icon is redrawn
  every tick with the **combined download + upload speed**, exactly like the
  reference app (`template app/`): a square white **bold "value over unit"
  bitmap** — e.g. `504` over `KB/s`, or `1.7` over `MB/s` — that appears next
  to the clock in the status bar. The reference shipped ~1,300 pre-rendered
  icons (`wkb###`/`wmb###`); this app draws the identical glyphs live on a
  Canvas (port of the reference renderer's text metrics, condensing and
  value ranges), so it stays crisp at any screen density.
- **Reference-style notification**
  - Line 1: `↓ <down>   ↑ <up>` (bytes-based KB/s · MB/s units)
  - Line 2: today's usage — WiFi (with network name) or Mobile, or a
    **"Warning: X MB used"** line when the optional monthly mobile-data
    limit is exceeded.
  - Notification is refreshed every tick while the screen is on and ~15 s
    while it is off; no wake locks.
- **Usage table** (main screen, reference layout):
  `Date | Mobile | WiFi | Total` rows for today plus ~30 archived days, with
  **Last 7 Days / Last 30 Days / This Month** summary rows.
- **Top of the notification list** — like the reference, the notification
  uses a silent `IMPORTANCE_HIGH` channel (not `MIN`) with no sound/vibration and is **re-posted every
  second**; the drawer sorts that stream by post time, so the speed
  notification stays at the top ahead of other apps' notifications.
- **Daily history** — every completed day is archived in a local
  `SharedPreferences` store (file `net`: `today1`/`today2` + `1<date>`/`2<date>`
  MiB floats, same data model as the reference app; older than 90 days is
  pruned automatically).
- **Monthly mobile-data limit** (optional) with a warning notification line.
- **Reset stats** and **Set data limit** dialogs.
- **Auto-resume after reboot** when the service was previously running
  (or when the separate boot toggle is on), like the reference app.

## Important platform notes

- **Status-bar icon**: regular apps cannot draw views inside the system
  status bar directly; the standard mechanism (also used by the reference
  app) is to draw the number into the *foreground notification's small
  icon*, which SystemUI displays next to the clock. No overlay permission
  (`SYSTEM_ALERT_WINDOW`) is needed.
- On Android 13+ the `POST_NOTIFICATIONS` permission is requested in-app; on
  Android 14+ the service declares the `specialUse` foreground-service type.
- Without the `ACCESS_FINE_LOCATION` runtime permission the WiFi name appears
  as `<unknown ssid>` on modern Android — the WiFi usage split still works
  (it does not depend on the name).
- **Battery/counter caveat**: `TrafficStats` counters can reset on reboot; the
  engine detects backwards jumps, rebases to zero for that tick, and continues
  (same strategy as the reference app). On devices where the global counters
  are unsupported, per-app (UID) counters are used and traffic is attributed
  to the active network type.

## Project layout

```
app/src/main/
├── AndroidManifest.xml      # no INTERNET permission -> offline by design
├── java/dev/netspeed/meter/
│   ├── SpeedService.kt      # 1 s tick loop, notification, date rolls
│   ├── Engine.kt            # counter sampling, mobile/wifi split, today usage
│   ├── Store.kt             # local prefs store: settings + daily history
│   ├── StatusBarIcon.kt     # live value-over-unit status-bar bitmap icon
│   ├── MainActivity.kt      # usage table + toggles + limit/reset dialogs
│   ├── Fmt.kt               # speed/amount formatting (KB/s, MB, GB units)
│   └── BootReceiver.kt      # auto-resume after reboot
└── res/                     # layout, strings, icons, theme
```

## Build & run

Requirements: JDK 17, Android SDK (e.g. `C:\Users\Mohammad\Android\Sdk`):

```
set JAVA_HOME=C:\Users\Mohammad\.jdks\jdk-17.0.20.1+1
./gradlew assembleDebug --offline     # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then flip **Live speed tracking** on; the notification and the usage table
fill automatically as traffic flows.

## Tuning

| Constant            | Default | Meaning                              |
|---------------------|---------|--------------------------------------|
| `TICK_MS`           | 1000 ms | Sampling / notification cadence (reference app uses 1 s) |
| flush cycle         | 60 ticks| Today's counters written to storage  |

## Privacy

Everything is computed and stored on the device. The merged manifest contains
no `INTERNET` permission, so the OS itself prevents any network access.
