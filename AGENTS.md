# Agent install: PRONET TV launcher

This repository is a ready-to-install Android TV home screen. Package id: **`ru.pronet.tv`**. Prebuilt APK: **`dist/PronetTV.apk`**. Do not install or depend on `io.freemytv`.

When a human says “install PRONET / the launcher on my TV box” and gives an IP (or USB), follow this file. Do not ask them to sideload from a store.

## Goal

1. The package `ru.pronet.tv` is installed (replace if already present).
2. It is the default home activity.
3. The PRONET screensaver is selected when the device allows it.
4. The home screen is shown.

Do **not** factory-reset the TV, do **not** uninstall unrelated apps, do **not** turn off USB debugging unless they ask.

## What you need on the computer

- `adb` (Android platform-tools). If missing, download Google platform-tools and use that `adb`.
- Network: the computer and the TV on the same LAN, **or** a USB cable with debugging.

## What the human must do on the TV (once)

1. Settings → System → About → tap the build number 7 times (developer mode).
2. Settings → Developer options → **USB debugging** on. If there is **Network debugging / ADB over network**, turn that on too.
3. Note the TV IPv4 address (Settings → Network).
4. When the prompt **Allow USB debugging?** appears, press Allow and tick **Always allow from this computer**.

If they already did this, skip it.

## Connect

USB:

```bash
adb devices
```

Network (replace the IP):

```bash
adb connect 192.168.1.50:5555
adb devices
```

Wait until the device is `device`, not `unauthorized`. If unauthorized, they must accept the dialog on the TV, then `adb connect` again.

If `connect` fails, try USB, or confirm the IP and that port 5555 is open.

## Install

From the clone of this repo (or after `gh release download`):

```bash
adb install -r dist/PronetTV.apk
```

`-r` replaces an older `ru.pronet.tv`. It cannot replace `io.freemytv` (different package). If a leftover Free My TV is still the home screen:

```bash
adb uninstall io.freemytv
```

only after `ru.pronet.tv` is installed and set as home.

## Make it the home screen

```bash
adb shell cmd package set-home-activity ru.pronet.tv/ru.pronet.tv.HomeActivity
adb shell settings put secure screensaver_components ru.pronet.tv/ru.pronet.tv.ScreensaverAlone
adb shell am start -n ru.pronet.tv/.HomeActivity
```

On Android 13+ the dream component may be `.Screensaver` instead of `.ScreensaverAlone`. If the screensaver does not change:

```bash
adb shell settings put secure screensaver_components ru.pronet.tv/ru.pronet.tv.Screensaver
```

If `set-home-activity` prints Success but Home still opens the factory launcher, tell the human to open the **PRONET** app from All Apps → Home Screen and switch to PRONET (the in-app switch uses the TV’s own ADB; they must Allow USB debugging on the TV itself).

## Verify

```bash
adb shell dumpsys package ru.pronet.tv | grep -E "versionName|versionCode"
adb shell cmd shortcut get-default-home
```

(If `get-default-home` is missing, `adb shell dumpsys package | grep HOME` / `cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`.)

You should see `ru.pronet.tv` and the home UI (clock, app shelf). First install seeds two bundled photos.

## Do not

- Do not change the application id; it must stay `ru.pronet.tv`.
- Do not sign a local rebuild and install it over this APK unless you use the same release keystore (not in this repo). A new keystore will fail as `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Prefer `dist/PronetTV.apk`.
- Do not disable random packages to “speed up” the TV.
- Do not collect or log the owner’s passwords, cabinet URLs, or VPN configs.

## Rebuild (optional)

JDK 17+ and Android SDK (`ANDROID_HOME`). From this directory:

```bash
# Linux/macOS
./build.sh

# Windows with WSL, after editing wsl-build.sh paths if needed
bash ./build.sh
```

Output: `PronetTV.apk` in the project root of a local build. Copy it to `dist/` if you are publishing.

City, language and weather defaults live in `config.properties` (`language=ru`, city Яблоновский). Change and rebuild only if the human asks.

## Identity cheat-sheet

| | |
|---|---|
| Package | `ru.pronet.tv` |
| Home | `ru.pronet.tv/.HomeActivity` |
| Settings | `ru.pronet.tv/.SettingsActivity` |
| Dream (Android 11–12L) | `ru.pronet.tv/.ScreensaverAlone` |
| Dream (other) | `ru.pronet.tv/.Screensaver` |
| APK | `dist/PronetTV.apk` |
| Log tag | `PronetTV` (`adb logcat -s PronetTV`) |
