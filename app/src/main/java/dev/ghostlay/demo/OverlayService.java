package dev.ghostlay.demo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
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
    private OverlayDrawView overlay;
    private WindowManager.LayoutParams lp;
    private boolean revealed;
    private int tries;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (overlay == null) {
            showOverlay();
        }
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Ghostlay overlay running")
                .setContentText("Tap to stop")
                .setContentIntent(stop)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    private void showOverlay() {
        wm = getSystemService(WindowManager.class);
        if (wm == null) {
            stopSelf();
            return;
        }

        OverlayDrawView view = new OverlayDrawView(this);
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT); // never paint an opaque window
        // Default test shapes (visible until config arrives): box + line in red.
        view.update(true, true, "Bottom", "Simple", "#ff3b30");
        view.setAlpha(0f); // reveal after SKIP_SCREENSHOT was attempted

        int screenW;
        int screenH;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            screenW = bounds.width();
            screenH = bounds.height();
        } else {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }

        int winFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

        lp = new WindowManager.LayoutParams(
                screenW,
                screenH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                winFlags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        overlay = view;
        wm.addView(view, lp);
        view.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        handler.post(applyRunnable);
        handler.post(configPollRunnable);
    }

    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            if (revealed && overlay != null) {
                SkipCapture.apply(overlay, true);
            }
        }
    };

    private final Runnable applyRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlay == null) return;
            if (SkipCapture.apply(overlay, true)) {
                overlay.setAlpha(1f);
                revealed = true;
                Log.i(TAG, "Overlay visible and hidden from capture (after " + tries + " retries)");
            } else if (++tries < MAX_TRIES) {
                handler.postDelayed(this, 50);
            } else {
                // Still show the overlay: a visible overlay is more useful than none.
                Log.e(TAG, "Could not set SKIP_SCREENSHOT; overlay will be visible in recordings");
                overlay.setAlpha(1f);
                revealed = true;
                Toast.makeText(OverlayService.this,
                        "Ghostlay: capture-skip failed, overlay is visible to recorders (see logcat)",
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    private final Runnable configPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlay == null) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final ConfigState st = fetchConfig();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (overlay == null) return;
                            if (st != null) {
                                overlay.update(st.line, st.box, st.linePosition, st.boxStyle, st.playerColor);
                                overlay.setVisibility(overlay.hasAnythingToDraw() ? View.VISIBLE : View.INVISIBLE);
                                if (revealed) {
                                    SkipCapture.apply(overlay, true);
                                }
                            } else {
                                // server not reachable: keep default test overlay visible
                                overlay.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                }
            }).start();
            handler.postDelayed(this, 700);
        }
    };

    private static class ConfigState {
        boolean line;
        boolean box;
        String linePosition = "Bottom";
        String boxStyle = "Simple";
        String playerColor = "#ff3b30";
    }

    private ConfigState fetchConfig() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CONFIG_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(400);
            conn.setReadTimeout(400);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            JSONObject root = new JSONObject(sb.toString());
            JSONObject esp = root.optJSONObject("esp");
            if (esp == null) return null;
            ConfigState st = new ConfigState();
            st.line = esp.optBoolean("line", false);
            st.box = esp.optBoolean("box", false);
            st.linePosition = esp.optString("linePosition", "Bottom");
            st.boxStyle = esp.optString("boxStyle", "Simple");
            st.playerColor = esp.optString("playerColor", "#ff3b30");
            return st;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (overlay != null) {
            try {
                overlay.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
        }
        super.onDestroy();
    }
}
