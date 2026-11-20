package dev.ghostlay.demo;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Entry point used when the module runs this apk without installing it:
 *
 * <pre>
 *   CLASSPATH=/data/adb/modules/ghost_esp/app/Ghostlay.apk \
 *       app_process --nice-name=ghostlay_overlay /system/bin dev.ghostlay.demo.BootMain
 * </pre>
 *
 * Nothing is registered with PackageManager: the classes are loaded straight
 * from the apk's classes.dex inside the module folder. Because the process is
 * started by root, the window-manager permission checks pass for uid 0, so the
 * overlay window can be added without any per-package appop grant.
 *
 * The module's service.sh waits for {@link #OK_MARKER} (window is really up)
 * and falls back to the classic installed-app path if {@link #FAIL_MARKER}
 * shows up instead, so a rom that refuses this cannot leave the user with no
 * overlay at all.
 */
public final class BootMain {
    private static final String TAG = "GhostlayAppProc";

    /** Written as the very first statement of main(), before anything can fail. */
    static final String START_MARKER = "/data/local/tmp/ghostlay_overlay.start";
    /** Written once the overlay window is actually attached. */
    static final String OK_MARKER = "/data/local/tmp/ghostlay_overlay.ok";
    /** Written with the stack trace when the overlay could not be started. */
    static final String FAIL_MARKER = "/data/local/tmp/ghostlay_overlay.fail";

    private static final int MAX_REVEAL_TRIES = 20;
    private static final String ESP_HOST = "127.0.0.1";
    private static final int ESP_PORT = 6969;

    /**
     * Window types tried in order until one is accepted.
     *
     * 2038 (TYPE_APPLICATION_OVERLAY) is the clean one, but adding it makes the
     * system note SYSTEM_ALERT_WINDOW for the calling uid, and uid 0 owns no
     * package: AppOpsService builds a SyncNotedAppOp with a null package name,
     * AnnotationValidations throws NPE inside addToDisplayAsUser and the window
     * never appears. The system types below are allowed for root through
     * INTERNAL_SYSTEM_WINDOW instead, so no appop is noted for them.
     */
    private static final int[] WINDOW_TYPES = {
            2038,   // TYPE_APPLICATION_OVERLAY
            2011,   // TYPE_SYSTEM_ERROR
            2004,   // TYPE_SYSTEM_ALERT
            2007,   // TYPE_SYSTEM_OVERLAY
            2003,   // TYPE_PHONE (same policy as SYSTEM_ALERT)
    };

    private BootMain() {
    }

    public static void main(String[] args) {
        // No marker here means the abort happened before this class ran at all
        // (VM creation, dex loading or class verification) - those messages go
        // to logcat only, never to stderr.
        writeMarker(START_MARKER, "pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid());
        try {
            Looper.prepareMainLooper();
            deleteMarker(OK_MARKER);
            deleteMarker(FAIL_MARKER);
            bypassHiddenApis();

            Context ctx = createSystemContext();
            if (ctx == null) throw new IllegalStateException("no system Context");

            new Overlay(ctx).show();

            writeMarker(OK_MARKER, "pid=" + android.os.Process.myPid()
                    + " uid=" + android.os.Process.myUid());
            Log.i(TAG, "overlay window added without installing the apk");
            Looper.loop();
        } catch (Throwable t) {
            reportFailure(t);
        }
    }

    // ------------------------------------------------------------- helpers ----

    private static void bypassHiddenApis() {
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("");
        } catch (Throwable t) {
            Log.w(TAG, "hidden api bypass unavailable: " + t);
        }
    }

    /**
     * A Context is only needed for resources and the WindowManager service, so
     * no ActivityThread attach (which would need a registered process) is done:
     * the plain system context is enough and works from a root app_process.
     */
    private static Context createSystemContext() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Object thread;
        try {
            thread = atClass.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            Method systemMain = atClass.getDeclaredMethod("systemMain");
            thread = systemMain.invoke(null);
        }
        Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(thread);
    }

    private static void writeMarker(String path, String text) {
        try {
            deleteMarker(path);
            try (FileWriter fw = new FileWriter(path, false)) {
                fw.write(text + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteMarker(String path) {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        } catch (Throwable ignored) {
        }
    }

    private static void reportFailure(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();
            System.err.println("[boot] overlay failed: " + trace);
            writeMarker(FAIL_MARKER, trace.length() > 3000 ? trace.substring(0, 3000) : trace);
        } catch (Throwable ignored) {
        }
        System.exit(1);
    }

    // ------------------------------------------------------------- overlay ----

    /** The overlay window plus the ESP feed, without a Service or an Activity. */
    static final class Overlay {
        private final Context ctx;
        private final Handler handler = new Handler(Looper.getMainLooper());

        private WindowManager wm;
        private EspOverlayView view;
        private WindowManager.LayoutParams lp;
        private boolean revealed;
        private int tries;
        private volatile boolean polling;
        private Thread pollThread;

        Overlay(Context ctx) {
            this.ctx = ctx;
        }

        void show() {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) throw new IllegalStateException("no WindowManager");

            Throwable last = null;
            for (int type : WINDOW_TYPES) {
                try {
                    attach(type);
                    Log.i(TAG, "overlay window added with type " + type);
                    return;
                } catch (Throwable t) {
                    last = t;
                    Log.w(TAG, "window type " + type + " not accepted: " + t);
                    detach();
                }
            }
            throw new IllegalStateException("no window type accepted", last);
        }

        /** One addView() attempt with the given window type. */
        private void attach(int type) {
            revealed = false;
            tries = 0;
            view = new EspOverlayView(ctx);
            view.setAlpha(0f);

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;

            wm.addView(view, lp);
            view.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
            handler.post(reveal);
            startPolling();
        }

        /** Removes whatever the failed attempt left behind before the next try. */
        private void detach() {
            handler.removeCallbacks(reveal);
            polling = false;
            Thread t = pollThread;
            pollThread = null;
            if (t != null) t.interrupt();
            View v = view;
            view = null;
            if (v == null) return;
            try {
                v.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            } catch (Throwable ignored) {
            }
            try {
                wm.removeViewImmediate(v);
            } catch (Throwable ignored) {
            }
        }

        private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> {
            EspOverlayView v = view;
            if (revealed && v != null) SkipCapture.apply(v, true);
        };

        private final Runnable reveal = new Runnable() {
            @Override
            public void run() {
                EspOverlayView v = view;
                if (v == null) return;
                if (SkipCapture.apply(v, true)) {
                    v.setAlpha(1f);
                    revealed = true;
                    Log.i(TAG, "ESP overlay hidden from capture after " + tries + " retries");
                } else if (++tries < MAX_REVEAL_TRIES) {
                    handler.postDelayed(this, 50);
                } else {
                    v.setAlpha(1f);
                    revealed = true;
                    Log.w(TAG, "capture-skip failed; showing the overlay anyway");
                }
            }
        };

        private void startPolling() {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    Socket sock = null;
                    try {
                        sock = new Socket();
                        sock.connect(new InetSocketAddress(ESP_HOST, ESP_PORT), 150);
                        sock.setTcpNoDelay(true);
                        sock.setSoTimeout(1000);
                        OutputStream os = sock.getOutputStream();
                        os.write(("GET /api/esp/stream HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
                        os.flush();
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(sock.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.length() == 0) break;   // end of the http headers
                        }
                        while (polling && (line = br.readLine()) != null) {
                            final String payload = line;
                            final EspOverlayView v = view;
                            if (v != null && payload.length() > 0) {
                                try {
                                    final JSONObject data = new JSONObject(payload);
                                    handler.post(() -> {
                                        EspOverlayView cur = view;
                                        if (cur != null) cur.updateFromJson(data);
                                    });
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (sock != null) {
                            try {
                                sock.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (!polling) break;
                    final EspOverlayView v = view;
                    if (v != null) {
                        handler.post(() -> {
                            EspOverlayView cur = view;
                            if (cur != null) cur.clear();
                        });
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "esp-stream");
            pollThread.setDaemon(true);
            pollThread.start();
        }

        @SuppressWarnings("unused")
        void stop() {
            polling = false;
            Thread t = pollThread;
            pollThread = null;
            if (t != null) t.interrupt();
            View v = view;
            view = null;
            if (wm != null && v != null) {
                try {
                    v.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
                    wm.removeView(v);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
