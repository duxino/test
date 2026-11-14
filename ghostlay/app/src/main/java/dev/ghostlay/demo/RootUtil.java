package dev.ghostlay.demo;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Tiny `su` wrapper. Never call from the main thread (the su prompt can block). */
final class RootUtil {
    private static final String TAG = "Ghostlay";

    private RootUtil() {}

    /** Runs one command as root; returns stdout+stderr, or null if su is unavailable / timed out. */
    static String su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return out.toString().trim();
        } catch (Exception e) {
            Log.w(TAG, "su failed: " + e);
            return null;
        }
    }

    static boolean hasRoot() {
        String r = su("id");
        return r != null && r.contains("uid=0");
    }

    /** Normal per-app permission grants (what the Settings UI would do). Undo: `appops reset <pkg>`. */
    static void grantPermissions(String pkg) {
        su("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
        su("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS");
    }
}
