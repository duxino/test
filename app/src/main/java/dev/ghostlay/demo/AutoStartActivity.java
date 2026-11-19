package dev.ghostlay.demo;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Entry point used by the Magisk module (service.sh) at boot.
 *
 * It is translucent and finishes immediately: the only job is to start the
 * foreground overlay service. Root starts it directly, so no launcher icon and
 * no user interaction are needed.
 */
public class AutoStartActivity extends Activity {
    private static final String TAG = "Ghostlay";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent i = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Log.i(TAG, "overlay service start requested");
        } catch (Throwable t) {
            Log.e(TAG, "autostart failed", t);
        }
        finish();
    }
}
