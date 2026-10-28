# PRONET TV: notes for coding agents

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
**PRONET** and the TV's original home screen. Same behaviour as [Free My TV](https://github.com/simbasjungles/free-my-tv):
the home screen, screensaver, watchdog, *Original* undo, and `pm disable-user --user 0` for a short list of promo apps.
The only intended difference is the application id **`ru.pronet.tv`** and the label **PRONET**.

A plain Android app in Java, built without Gradle by `build.sh`. Prebuilt APK for installs: **`dist/PronetTV.apk`**
(same signing key as previous PRONET installs). Prefer that file unless they asked for a custom city or TMDB key, in
which case edit `config.properties` and run `./build.sh`.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Always

- Package id is `ru.pronet.tv`. Do not install or depend on `io.freemytv`. If leftover Free My TV is still home,
  finish PRONET first, then `adb uninstall io.freemytv` only if they agree.
- Google TV's setup app (`setupwraith`) goes off before the stock home screen and comes back after it, everywhere
  (`targets` order, `applyOriginal`, `revertHomeNow`, `verifyNow`): with the home screen off and it on, its
  RecoveryActivity takes Home. If it can't go off, the home screen stays on. On Amazon Fire TV the switch refuses
  PRONET mode; no `com.amazon.*` package is ever turned off.
- Never turn off an app that saved a Wi-Fi network (`wifiOwners` in `HomeMode`). When the Wi-Fi service doesn't
  answer, the switch stops on Wi-Fi.
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode` on the TV
  (the in-app switch), so *Original* can bring it back. Do not run your own `pm disable` list from the computer.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
- Don't factory-reset. Don't turn off USB debugging unless they ask.

## Installing it for someone (Android TV / Google TV)

This is the same sequence as Free My TV. The last step on the TV is what actually “optimizes” the box.

1. **Ask** for the TV's IP address (and city only if they want a custom weather default).
2. **Tools:** `adb` (Android platform-tools). You do **not** need to install JDK/SDK if you use `dist/PronetTV.apk`.
   If you must rebuild: JDK 17+, Android SDK (`build-tools` 34+, `platforms;android-34`), then `./build.sh`.
3. **On the TV** (they do it, once): Settings → System → About → tap the build number 7 times. Developer options →
   **USB debugging** on (and network / wireless debugging if you use Wi-Fi). Write down the IPv4 address.
4. **Connect:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Wait until `adb devices` shows `device`, not `unauthorized`.
5. **Install:** from this repo:

   ```bash
   adb install -r dist/PronetTV.apk
   ```

   If it hangs, Google Play Protect may be asking on the TV: *More details* → *Install anyway*.
   On TCL, also:

   ```bash
   adb shell "appops set ru.pronet.tv AUTO_START allow || appops set ru.pronet.tv APP_AUTO_START allow"
   ```

   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`. If both appops names answer `Unknown operation string`, skip.
   Some TCL Android 11 boxes have **TGuard**, which can kill `ModeJob` during “Measuring…”. Tell them: if settings
   close and the stock home stays, wait a minute or two, then press Home; PRONET opening means the switch went through.
   Chromecast with Google TV (Android 14) often needs *Wireless debugging* + `adb pair`, then `adb tcpip 5555`.
6. **Tell them what the switch does** before they make it: the home screen and screensaver change; a short list of
   promo / demo / telemetry apps is **turned off** (never uninstalled); *Original* in PRONET brings it all back.
   Picture, sound, Play Store, live TV and protected system apps stay. On TCL, Netflix/YouTube/Prime buttons can
   keep working through a small accessibility service (*Remote Buttons*); *Original* turns it off.
7. **They switch on the TV.** Do **not** run `cmd package set-home-activity` from the computer (that skips
   `HomeMode`: no app list, no screensaver bookkeeping, no undo state).

   Before they tap, `adb disconnect <IP>:5555` (Android 8–10 can hand the debugging prompt to the computer). Then
   they open **PRONET** (All Apps) → **Home Screen** → **PRONET**. The TV asks *Allow USB debugging?* again, now
   for the app itself: tick *Always allow* and accept.

   If Wi-Fi was saved during first setup, the switch stops and asks them to forget the network and reconnect; then
   try again. Out-of-range networks: `dumpsys wifi` blocks with `cname=com.google.android.tungsten.setupwraith`,
   then with their OK `adb shell cmd wifi forget-network <id>`.
8. **Check:** `adb connect` again if needed. `adb logcat -s PronetTV` shows what the switch did. Home should open
   PRONET. *Home Screen → Original* is the undo.

## USB stick

Android TV does not autorun files from a flash drive. Copy `dist/PronetTV.apk` onto the stick; they install it from
the TV's file manager, then do step 7 (Home Screen → PRONET) with USB debugging on. Without that tap, promo apps
stay enabled.

## Rebuild (optional)

JDK 17+, `ANDROID_HOME`. `./build.sh` writes `PronetTV.apk`. Copy it to `dist/` if you are publishing. Sign updates
with the same keystore as `dist/PronetTV.apk` or Android will refuse (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). The
keystore is not in this repository.

## Adapting it to another TV

Read-only first:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell pm list packages -s
```

Then only if needed: `HomeMode.DEBLOAT` (promo/demo/telemetry), `HomeMode.PROTECTED` (picture, sound, inputs, remote;
when in doubt, protect), `HomeApps.SHELF`. Build, install, have them switch on the TV, read `adb logcat -s PronetTV`,
have them switch back to Original.

## Identity

| | |
|---|---|
| Package | `ru.pronet.tv` |
| Settings (app icon) | `ru.pronet.tv/.SettingsActivity` |
| Home (after the switch) | `ru.pronet.tv/.HomeActivity` |
| Dream (API 30–32) | `ru.pronet.tv/.ScreensaverAlone` |
| Dream (other) | `ru.pronet.tv/.Screensaver` |
| APK | `dist/PronetTV.apk` |
| Log | `adb logcat -s PronetTV` |
