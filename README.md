# LockGuard

A root-capable, multipurpose Android security app. **First feature:** if the
lock-screen passcode is entered wrong **3 or more times in a row**, it sounds a
loud alarm on the alarm stream (max volume) for **10 seconds**.

Built and targeted for **GrapheneOS** with a Magisk fork + re-locked bootloader
(avbroot), but the detection works on any AOSP-based Android.

---

## How it works

- **`LockMonitorAdmin`** — a `DeviceAdminReceiver` with the `watch-login`
  policy. Android calls `onPasswordFailed()` on every wrong lock-screen attempt
  and `onPasswordSucceeded()` on a correct one. It counts consecutive failures
  and, at 3+, signals the service. (Detection via device admin needs **no
  root**.)
- **`MonitorService`** — a persistent foreground service. Because it's already
  in the foreground, it can play audio on demand even while locked, sidestepping
  Android's background-start limits. It plays the default alarm tone looped for
  10 s under a wake lock, with the alarm stream forced to max.
- **`BootReceiver`** — restarts the monitor after reboot.
- **`MainActivity`** — setup UI: enable device admin, start the service, request
  battery-optimisation exemption, and a **Test alarm** button.

Where **root** earns its place (see *Hardening* below): persistence,
uninstall-protection, auto-activating the admin, and forcing sound past DND.

---

## Getting the APK

You don't need a local toolchain — pick whichever is easiest.

### Option A — GitHub Actions (recommended, zero setup)
1. Create a new **empty** repo on your GitHub (no README).
2. Push this project to it:
   ```bash
   git init && git add . && git commit -m "LockGuard initial"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. Open the repo's **Actions** tab → wait for *Build APK* to finish (~2–4 min).
4. Open the run → **Artifacts** → download **`LockGuard-debug-apk`**. Unzip →
   `app-debug.apk`.

The workflow (`.github/workflows/android.yml`) installs the Android SDK on
GitHub's runner and builds on every push. You can also trigger it by hand from
the Actions tab (*Run workflow*).

### Option B — Android Studio
Open the project folder → let it sync → **Build ▸ Build App Bundle(s) / APK(s) ▸
Build APK(s)**. Or just press **Run** with your phone connected.

### Option C — Local command line
Needs JDK 17 + the Android SDK (`ANDROID_HOME` set):
```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

> **Why not build it in a restricted sandbox?** That sandbox blocks
> `dl.google.com` and Maven Central at the network layer, so the Android SDK and
> Gradle/AndroidX plugins can't be fetched there. The build has to run somewhere
> with internet — your machine, Android Studio, or CI.

---

## Install & set up on the phone

1. Sideload the APK (`adb install app-debug.apk`, or open it in a file manager).
2. Open **LockGuard** →
   - **1 · Enable device admin** → approve the system prompt.
   - **2 · Start protection service**.
   - **3 · Ignore battery optimisations** → allow (keeps it alive when locked).
3. Tap **Test alarm now** to confirm it's loud.
4. Lock the phone and enter the wrong PIN 3×.

With **root**, you can auto-activate the admin instead of tapping through step 1:
```bash
su -c 'dpm set-active-admin com.lockguard/.LockMonitorAdmin'
```

---

## Hardening for a proper root build (optional next step)

To make it tamper-resistant and immune to background/battery limits on
GrapheneOS, ship it **systemized** as a Magisk module (drops the APK into
`/system/priv-app`). That makes it a privileged app — exempt from
foreground-service background-start restrictions, started reliably at boot, and
not removable by a thief. Ask and I'll generate the flashable module zip
(install = systemize + auto-activate admin in one step).

For a **custom, louder siren**, drop an audio file at
`app/src/main/res/raw/siren.ogg` and point `MonitorService` at
`R.raw.siren` instead of the default alarm URI.

---

## Config

- Failure threshold: `LockMonitorAdmin.THRESHOLD` (default `3`)
- Alarm length: `MonitorService.ALARM_DURATION_MS` (default `10_000` ms)
- Package / appId: `com.lockguard` (rename in `app/build.gradle.kts` + package dirs)

## Build setup
Gradle 8.9 · AGP 8.6.1 · Kotlin 2.0.21 · compileSdk/targetSdk 35 · minSdk 29 · JDK 17
