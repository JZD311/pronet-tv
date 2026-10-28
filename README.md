# PRONET TV

Android TV / Google TV home screen for PRONET set-top boxes. Package **`ru.pronet.tv`**. No ads. Own photos on the home screen and screensaver. Weather, app shelf, mouse and remote.

Fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT). Application id is **`ru.pronet.tv`**, not `io.freemytv`.

**Agents:** follow **[AGENTS.md](AGENTS.md)** — clone this repo, `adb install -r dist/PronetTV.apk`, set home to `ru.pronet.tv/.HomeActivity`.

## Install (human)

1. On the TV: Developer options → USB debugging (and network ADB if you use Wi‑Fi).
2. On a computer with [platform-tools](https://developer.android.com/tools/releases/platform-tools):

```bash
git clone https://github.com/JZD311/pronet-tv.git
cd pronet-tv
adb connect TV_IP:5555
adb install -r dist/PronetTV.apk
adb shell cmd package set-home-activity ru.pronet.tv/ru.pronet.tv.HomeActivity
adb shell am start -n ru.pronet.tv/.HomeActivity
```

Replace `TV_IP` with the box address.

Or tell any coding agent:

> Install github.com/JZD311/pronet-tv on my TV at 192.168.x.x. Follow AGENTS.md.

## Build

JDK 17+, Android SDK. `./build.sh` (or WSL `wsl-build.sh`). Do not commit a private keystore; the published APK is `dist/PronetTV.apk`.

## License

MIT — see `LICENSE`.
