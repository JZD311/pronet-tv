# PRONET TV

A fast, ad-free home screen for Google TV and Android TV, based on [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT).

Same install, same home-screen switch, same *Original* undo, same reversible app list (`pm disable-user`). The app on
the TV is **PRONET** (`ru.pronet.tv`), not `io.freemytv`.

[**Install**](#install) · [Agents](AGENTS.md) · [CLAUDE.md](CLAUDE.md)

## Install

About ten minutes. You need a Google TV or Android TV (Android 8 or newer) and a computer on the same Wi-Fi.

### The easy way: an AI assistant

1. **On the TV**, turn on developer mode: Settings → System → About → press *Android TV OS build* 7 times. Then Settings → System → Developer options → turn on **USB debugging**. Write down the TV's IP address.
2. **On your computer**, open an AI coding assistant and say:

   > Install github.com/JZD311/pronet-tv on my TV at 192.168.1.50. Follow AGENTS.md.

   Swap in the TV's IP. It uses `dist/PronetTV.apk` and walks you through the switch on the TV.
3. **On the TV**, open **PRONET** → **Home Screen** → **PRONET**. That's when the TV asks *Allow USB debugging?* for PRONET: tick *Always allow* and accept. Promo apps are turned off here (they come back under *Original*).

### The ready APK

```bash
git clone https://github.com/JZD311/pronet-tv.git
cd pronet-tv
adb connect 192.168.1.50:5555
adb install -r dist/PronetTV.apk
```

On TCL:

```bash
adb shell "appops set ru.pronet.tv AUTO_START allow || appops set ru.pronet.tv APP_AUTO_START allow"
```

Then on the TV: **PRONET → Home Screen → PRONET** (`adb disconnect` first on Android 8–10). Do not set the default home from the computer; that skip would leave the original app list running.

On Android 14, Play Protect may show *Risky app blocked*: *More details* → *Install anyway*.

### USB stick

Copy `dist/PronetTV.apk` onto a flash drive, install it from the TV's file manager, then the same **Home Screen → PRONET** step (USB debugging on). The stick cannot autorun or disable apps by itself.

## Undo

On the TV: **PRONET → Home Screen → Original**. Factory home, screensaver and every app it turned off come back. If the new home crashes three times in five minutes, it switches back by itself.

To remove it for good, switch to *Original* first, then uninstall PRONET.

## Build

JDK 17+, Android SDK. `./build.sh` → `PronetTV.apk`. Do not commit a keystore; published installs use `dist/PronetTV.apk`.

## License

MIT — see `LICENSE`. Fork of Free My TV.
