package dev.ghostlay.demo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Full-screen ESP overlay fed from the module's native server.
 *
 * Started automatically by the Magisk module (service.sh -> AutoStartActivity),
 * so it waits for the overlay permission instead of assuming a user tapped
 * "allow" first.
 */
public class OverlayService extends Service {
    static final String ACTION_STOP = "dev.ghostlay.demo.STOP";
    private static final String TAG = "Ghostlay";
    private static final String CHANNEL = "ghostlay";
    private static final int MAX_TRIES = 20;
    private static final int PERM_TRIES = 60;
    /** Marker read by the module's service.sh so it does not fight the stop button. */
    private static final String STOP_FLAG = "overlay_stopped";
    private static final String ESP_HOST = "127.0.0.1";
    private static final int ESP_PORT = 6969;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;

    private EspOverlayView espOverlay;
    private WindowManager.LayoutParams espLp;
    private boolean espRevealed;
    private int espTries;
    private int permTries;
    private volatile boolean espPolling;
    private Thread espPollThread;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            try { new java.io.File(getFilesDir(), STOP_FLAG).createNewFile(); } catch (Exception ignored) { }
            stopSelf();
            return START_NOT_STICKY;
        }
        try { new java.io.File(getFilesDir(), STOP_FLAG).delete(); } catch (Exception ignored) { }
        startInForeground();
        ensureOverlay();
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

    /** Waits for the module's root grant before the first addView. */
    private void ensureOverlay() {
        if (espOverlay != null) return;
        if (wm == null) wm = getSystemService(WindowManager.class);
        if (!Settings.canDrawOverlays(this)) {
            if (++permTries > PERM_TRIES) {
                Log.e(TAG, "overlay permission missing after " + PERM_TRIES + "s; giving up");
                return;
            }
            handler.postDelayed(this::ensureOverlay, 1000);
            return;
        }
        permTries = 0;
        showEspOverlay();
    }

    private void showEspOverlay() {
        if (wm == null) wm = getSystemService(WindowManager.class);
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
        } catch (Throwable t) {
            Log.e(TAG, "Failed to show ESP overlay, retrying", t);
            espOverlay = null;
            if (++permTries <= PERM_TRIES) handler.postDelayed(this::ensureOverlay, 1000);
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
        super.onDestroy();
    }
}
