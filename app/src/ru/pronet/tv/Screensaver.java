package ru.pronet.tv;

import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off; Home Settings can ask for a delay (screensaver_timeout). Any
 * key exits; with Watch from the screensaver on (Home Settings), OK first opens the
 * title on screen in its app.
 */
public class Screensaver extends DreamService {
    private DreamView view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        Photos.seedBundled(this);
        setInteractive(Prefs.saverWatch());   // interactive: the keys come here instead of waking the TV
        setFullscreen(true);
        setScreenBright(true);
        view = new DreamView(this);
        setContentView(view);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            finish();
            return true;
        }
        return true;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int button = event.getActionButton();
        if (button == MotionEvent.BUTTON_SECONDARY || button == MotionEvent.BUTTON_TERTIARY
                || (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
            finish();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isInteractive()) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        // OK acts when it's released, so the app it opens doesn't get the release; any other key closes at once.
        if (ok ? event.getAction() == KeyEvent.ACTION_UP : event.getAction() == KeyEvent.ACTION_DOWN) {
            if (ok && view != null) view.watch();
            finish();
        }
        return true;
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
