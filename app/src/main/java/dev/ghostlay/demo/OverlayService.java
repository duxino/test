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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class OverlayService extends Service {
    static final String ACTION_STOP = "dev.ghostlay.demo.STOP";
    private static final String TAG = "Ghostlay";
    private static final String CHANNEL = "ghostlay";
    private static final int MAX_TRIES = 20;
    private static final String ESP_HOST = "127.0.0.1";
    private static final int ESP_PORT = 6969;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView overlay;
    private WindowManager.LayoutParams lp;
    private boolean revealed;
    private int tries;

    // Full-screen ESP overlay (all except bone), fed from /api/esp.
    private EspOverlayView espOverlay;
    private WindowManager.LayoutParams espLp;
    private boolean espRevealed;
    private int espTries;
    private volatile boolean espPolling;
    private Thread espPollThread;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (overlay == null) showOverlay();
        if (espOverlay == null) showEspOverlay();
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
                overlay.setAlpha(1f);
                revealed = true;
                Log.e(TAG, "Could not set SKIP_SCREENSHOT; showing overlay anyway");
                Toast.makeText(OverlayService.this, "Ghostlay: capture-skip failed (showing anyway)", Toast.LENGTH_LONG).show();
            }
        }
    };

    private void showEspOverlay() {
        if (wm == null) return;
        try {
            EspOverlayView view = new EspOverlayView(this);
            view.setAlpha(0f);

            espLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            espLp.gravity = Gravity.TOP | Gravity.START;

            espOverlay = view;
            espRevealed = false;
            espTries = 0;
            wm.addView(view, espLp);
            view.getViewTreeObserver().addOnGlobalLayoutListener(espLayoutListener);
            handler.post(espApplyRunnable);
            startEspPolling();
            Log.i(TAG, "ESP overlay added (waiting for capture-skip)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show ESP overlay", e);
            espOverlay = null;
        }
    }

    private void startEspPolling() {
        stopEspPolling();
        espPolling = true;
        espPollThread = new Thread(() -> {
            while (espPolling) {
                Socket sock = null;
                try {
                    sock = new Socket();
                    sock.connect(new InetSocketAddress(ESP_HOST, ESP_PORT), 150);
                    sock.setTcpNoDelay(true);
                    sock.setSoTimeout(1000);
                    OutputStream os = sock.getOutputStream();
                    os.write(("GET /api/esp/stream HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    os.flush();
                    BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
                    // skip HTTP headers
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.length() == 0) break;
                    }
                    while (espPolling && (line = br.readLine()) != null) {
                        final String payload = line;
                        final EspOverlayView view = espOverlay;
                        if (view != null && payload.length() > 0) {
                            try {
                                final JSONObject data = new JSONObject(payload);
                                handler.post(() -> {
                                    if (espOverlay != null) espOverlay.updateFromJson(data);
                                });
                            } catch (Exception ignored) { }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (sock != null) {
                        try { sock.close(); } catch (Exception ignored) { }
                    }
                }
                if (!espPolling) break;
                final EspOverlayView view = espOverlay;
                if (view != null) {
                    handler.post(() -> { if (espOverlay != null) espOverlay.clear(); });
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
            }
        }, "esp-stream");
        espPollThread.setDaemon(true);
        espPollThread.start();
    }

    private void stopEspPolling() {
        espPolling = false;
        Thread t = espPollThread;
        espPollThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private final ViewTreeObserver.OnGlobalLayoutListener espLayoutListener = () -> {
        if (espRevealed && espOverlay != null) SkipCapture.apply(espOverlay, true);
    };

    private final Runnable espApplyRunnable = new Runnable() {
        @Override public void run() {
            if (espOverlay == null) return;
            if (SkipCapture.apply(espOverlay, true)) {
                espOverlay.setAlpha(1f);
                espRevealed = true;
                Log.i(TAG, "ESP overlay hidden from capture after " + espTries + " retries");
            } else if (++espTries < MAX_TRIES) {
                handler.postDelayed(this, 50);
            } else {
                espOverlay.setAlpha(1f);
                espRevealed = true;
                Log.w(TAG, "ESP overlay capture-skip failed; showing anyway");
            }
        }
    };

    // ESP data is received via persistent /api/esp/stream connection in startEspPolling().

    private void hideEspOverlay() {
        stopEspPolling();
        if (wm != null && espOverlay != null) {
            try {
                espOverlay.getViewTreeObserver().removeOnGlobalLayoutListener(espLayoutListener);
                wm.removeView(espOverlay);
            } catch (Exception ignored) { }
        }
        espOverlay = null;
        espRevealed = false;
        espTries = 0;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideEspOverlay();
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
