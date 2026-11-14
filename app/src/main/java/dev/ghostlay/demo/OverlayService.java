package dev.ghostlay.demo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class OverlayService extends Service {
    static final String ACTION_STOP = "dev.ghostlay.demo.STOP";
    private static final String TAG = "Ghostlay";
    private static final String CHANNEL = "ghostlay";
    private static final int MAX_TRIES = 20;
    private static final String CONFIG_URL = "http://127.0.0.1:6969/api/config";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView overlay;
    private WindowManager.LayoutParams lp;
    private boolean revealed;
    private int tries;

    // ESP box test overlay (center screen), shown when esp.box is true in config.
    // Same method as Hello World: its own overlay window, alpha revealed after SkipCapture.
    private View boxOverlay;
    private WindowManager.LayoutParams boxLp;
    private boolean boxRevealed;
    private int boxTries;
    private boolean boxWanted;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (overlay == null) showOverlay();
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_MIN));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Ghostlay is running")
                .setContentText("Tap to stop")
                .setContentIntent(stop)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void showOverlay() {
        wm = getSystemService(WindowManager.class);
        float d = getResources().getDisplayMetrics().density;

        TextView tv = new TextView(this);
        tv.setText("Hello World");
        tv.setTextSize(22);
        tv.setTextColor(Color.BLACK);
        int pad = (int) (20 * d);
        tv.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(16 * d);
        tv.setBackground(bg);
        tv.setAlpha(0f); // invisible until the capture-skip flag is on, so no first frame leaks

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (40 * d);
        lp.y = (int) (200 * d);

        tv.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x; startY = lp.y;
                        downX = e.getRawX(); downY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        wm.updateViewLayout(v, lp);
                        return true;
                }
                return false;
            }
        });

        overlay = tv;
        wm.addView(tv, lp);
        tv.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        handler.post(applyRunnable);
        handler.post(configPollRunnable);
    }

    /** Re-applies the flag after relayouts (rotation, surface re-creation). */
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> {
        if (revealed && overlay != null) SkipCapture.apply(overlay, true);
    };

    private final Runnable applyRunnable = new Runnable() {
        @Override public void run() {
            if (overlay == null) return;
            if (SkipCapture.apply(overlay, true)) {
                overlay.setAlpha(1f);
                revealed = true;
                Log.i(TAG, "Overlay hidden from capture after " + tries + " retries");
            } else if (++tries < MAX_TRIES) {
                handler.postDelayed(this, 50);
            } else {
                // Fail closed: never keep an overlay that could be captured.
                Log.e(TAG, "Could not set SKIP_SCREENSHOT; check logcat tag Ghostlay");
                Toast.makeText(OverlayService.this, "Ghostlay: capture-skip failed (see logcat)", Toast.LENGTH_LONG).show();
                stopSelf();
            }
        }
    };

    /** Polls TX9 config on port 6969 and shows/hides the center ESP box. */
    private final Runnable configPollRunnable = new Runnable() {
        @Override public void run() {
            new Thread(() -> {
                final boolean boxOn = fetchEspBoxEnabled();
                handler.post(() -> {
                    boxWanted = boxOn;
                    if (boxOn) {
                        ensureEspBox();
                    } else {
                        hideEspBox();
                    }
                    if (wm != null) { // service still alive
                        handler.postDelayed(configPollRunnable, 700);
                    }
                });
            }).start();
        }
    };

    private boolean fetchEspBoxEnabled() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CONFIG_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(400);
            conn.setReadTimeout(400);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            if (conn.getResponseCode() != 200) return false;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject esp = new JSONObject(sb.toString()).optJSONObject("esp");
            return esp != null && esp.optBoolean("box", false);
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void ensureEspBox() {
        if (wm == null) return;
        if (boxOverlay == null) {
            showEspBox();
        } else if (boxRevealed) {
            SkipCapture.apply(boxOverlay, true);
        }
    }

    private void showEspBox() {
        try {
            View view = new EspBoxView(this);
            view.setAlpha(0f); // same as Hello World: reveal after capture-skip is applied

            boxLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            boxLp.gravity = Gravity.CENTER;

            boxOverlay = view;
            boxRevealed = false;
            boxTries = 0;
            wm.addView(view, boxLp);
            view.getViewTreeObserver().addOnGlobalLayoutListener(boxLayoutListener);
            handler.post(boxApplyRunnable);
            Log.i(TAG, "ESP box overlay added (waiting for capture-skip)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show ESP box overlay", e);
            boxOverlay = null;
        }
    }

    private final ViewTreeObserver.OnGlobalLayoutListener boxLayoutListener = () -> {
        if (boxRevealed && boxOverlay != null) SkipCapture.apply(boxOverlay, true);
    };

    /** Same reveal method as Hello World, but failure does not stop the service. */
    private final Runnable boxApplyRunnable = new Runnable() {
        @Override public void run() {
            if (boxOverlay == null || !boxWanted) return;
            if (SkipCapture.apply(boxOverlay, true)) {
                boxOverlay.setAlpha(1f);
                boxRevealed = true;
                Log.i(TAG, "ESP box hidden from capture after " + boxTries + " retries");
            } else if (++boxTries < MAX_TRIES) {
                handler.postDelayed(this, 50);
            } else {
                // Show it anyway so the test is visible even if capture-skip is unavailable.
                boxOverlay.setAlpha(1f);
                boxRevealed = true;
                Log.w(TAG, "ESP box capture-skip failed; showing box anyway");
                Toast.makeText(OverlayService.this, "Ghostlay: box shown (capture-skip failed)", Toast.LENGTH_SHORT).show();
            }
        }
    };

    private void hideEspBox() {
        if (wm != null && boxOverlay != null) {
            try {
                boxOverlay.getViewTreeObserver().removeOnGlobalLayoutListener(boxLayoutListener);
                wm.removeView(boxOverlay);
            } catch (Exception ignored) { }
        }
        boxOverlay = null;
        boxRevealed = false;
        boxTries = 0;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideEspBox();
        if (overlay != null) {
            try {
                overlay.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
                wm.removeView(overlay);
            } catch (Exception ignored) { }
            overlay = null;
        }
        super.onDestroy();
    }
}
