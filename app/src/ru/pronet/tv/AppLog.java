package ru.pronet.tv;

import android.util.Log;

/** The app's log (adb logcat -s PronetTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("PronetTV", message);
    }
}
