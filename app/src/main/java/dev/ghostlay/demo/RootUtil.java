package dev.ghostlay.demo;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Tiny `su` wrapper. Never call from the main thread (the su prompt blocks). */
final class RootUtil {
    private static final String TAG = "Ghostlay";

    // Magisk / SuperSU / KernelSU put `su` in different places; PATH from an app is minimal.
    private static final String[] SU_PATHS = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su"
    };

    private static String sLastError = "";

    private RootUtil() {
    }

    static String lastError() {
        return sLastError;
    }

    /** Runs one command as root; returns stdout+stderr, or null if su is unavailable / denied / timed out. */
    static String su(String cmd) {
        sLastError = "";
        boolean foundSu = false;
        for (String path : SU_PATHS) {
            try {
                ProcessBuilder pb = new ProcessBuilder(path, "-c", cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                // Wait first: the grant prompt can take a while; never block on readLine.
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    sLastError = "su prompt timed out";
                    continue;
                }
                foundSu = true;
                StringBuilder out = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
                r.close();
                String text = out.toString().trim();
                if (text.length() > 0) return text;
                sLastError = "su returned no output";
            } catch (Exception e) {
                // su binary not at this path: try the next one.
                Log.w(TAG, "su path failed: " + path + " (" + e + ")");
            }
        }
        if (!foundSu) sLastError = "no su binary (device not rooted?)";
        return null;
    }

    static boolean hasRoot() {
        String out = su("id");
        boolean root = out != null && out.contains("uid=0");
        if (!root && out != null) sLastError = "su denied: " + out;
        return root;
    }

    /** "granted", "denied", or "no su binary". Also triggers the Magisk/SuperSU prompt. */
    static String status() {
        String out = su("id");
        if (out == null) return sLastError.length() > 0 ? sLastError : "no root";
        if (out.contains("uid=0")) return "granted";
        return "denied (" + out.replace('\n', ' ') + ")";
    }

    /** Normal per-app permission grants (what the Settings UI would do). Undo: `appops reset <pkg>`. */
    static void grantPermissions(String pkg) {
        su("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
        su("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS");
    }
}
