package ru.pronet.tv;

import android.accessibilityservice.AccessibilityService;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 * Right-click and the mouse Back button become the system Back, so they leave apps that ignore a mouse click.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (mouseBack(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP) performGlobalAction(GLOBAL_ACTION_BACK);
            return true;
        }
        return RemoteKeys.onKey(this, event);
    }

    private static boolean mouseBack(KeyEvent e) {
        int src = e.getSource();
        if ((src & InputDevice.SOURCE_MOUSE) == 0 && (src & InputDevice.SOURCE_MOUSE_RELATIVE) == 0) return false;
        int code = e.getKeyCode();
        return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
                || code == KeyEvent.KEYCODE_BUTTON_2;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
