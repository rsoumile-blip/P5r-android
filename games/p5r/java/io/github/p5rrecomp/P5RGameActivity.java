package io.github.p5rrecomp;

import android.app.NativeActivity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;

/** Native game activity with a deliberate double-Back exit to the launcher. */
public final class P5RGameActivity extends NativeActivity {
    private static final long BACK_CONFIRM_MS = 2500;
    private long lastBackPress;
    private Toast backToast;

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) return true;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                long now = SystemClock.elapsedRealtime();
                if (lastBackPress != 0 && now - lastBackPress <= BACK_CONFIRM_MS) {
                    lastBackPress = 0;
                    if (backToast != null) backToast.cancel();
                    finish();
                } else {
                    lastBackPress = now;
                    backToast = Toast.makeText(this,
                            "Unsaved progress may be lost. Press Back again to exit.", Toast.LENGTH_LONG);
                    backToast.show();
                }
                return true;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
