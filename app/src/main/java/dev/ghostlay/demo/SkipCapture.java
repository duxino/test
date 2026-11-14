package dev.ghostlay.demo;

import android.os.Build;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Marks a window's layer with SurfaceControl's SKIP_SCREENSHOT flag.
 *
 * SurfaceFlinger leaves such a layer out of screenshots, display mirroring and
 * screen recording (anything rendered to a non-primary display), while the
 * physical screen still shows it.
 *
 * No external dependency: the hidden-API exemption is applied in-process with
 * plain reflection (VMRuntime.setHiddenApiExemptions). Nothing is persisted;
 * the flag lives on the layer and dies with the window.
 */
final class SkipCapture {
    private static final String TAG = "Ghostlay";
    private static final int SKIP_SCREENSHOT = 0x00000040; // SurfaceControl.SKIP_SCREENSHOT (@hide)

    private static boolean sTried, sReady;
    private static Method sGetViewRoot, sGetSurfaceControl, sSetSkip, sSetFlags, sApply;
    private static Constructor<?> sTxCtor;

    private SkipCapture() {
    }

    /**
     * Allows this process to look up the hidden android.view members we need.
     * Uses the classic double-reflection trick so the lookup itself is not blocked.
     */
    private static boolean exemptHiddenApis() {
        String[] exemptions = new String[]{
                "Landroid/view/",
                "Ldalvik/system/"
        };
        try {
            Method classForName = Class.class.getDeclaredMethod("forName", String.class);
            Method classGetDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);

            Class<?> vmRuntimeClass = (Class<?>) classForName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) classGetDeclaredMethod.invoke(
                    vmRuntimeClass, "getRuntime", (Object) new Class[0]);
            Method setHiddenApiExemptions = (Method) classGetDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", (Object) new Class[]{String[].class});

            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, (Object) exemptions);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "hidden-api exemption via VMRuntime failed on SDK " + Build.VERSION.SDK_INT, t);
        }

        // Fallback: direct call (works on some builds/ROMs without the extra indirection).
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            Method setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            getRuntime.setAccessible(true);
            setHiddenApiExemptions.setAccessible(true);
            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, (Object) exemptions);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "hidden-api exemption fallback failed on SDK " + Build.VERSION.SDK_INT, t);
        }
        return false;
    }

    private static synchronized boolean init() {
        if (sTried) return sReady;
        sTried = true;
        try {
            exemptHiddenApis();

            sGetViewRoot = View.class.getDeclaredMethod("getViewRootImpl");
            sGetViewRoot.setAccessible(true);

            Class<?> viewRoot = Class.forName("android.view.ViewRootImpl");
            sGetSurfaceControl = viewRoot.getDeclaredMethod("getSurfaceControl");
            sGetSurfaceControl.setAccessible(true);

            Class<?> tx = Class.forName("android.view.SurfaceControl$Transaction");
            sTxCtor = tx.getDeclaredConstructor();
            sTxCtor.setAccessible(true);
            sApply = tx.getDeclaredMethod("apply");
            sApply.setAccessible(true);

            try {
                sSetSkip = tx.getDeclaredMethod("setSkipScreenshot", SurfaceControl.class, boolean.class);
                sSetSkip.setAccessible(true);
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "setSkipScreenshot missing on SDK " + Build.VERSION.SDK_INT + ", using setFlags");
                sSetFlags = tx.getDeclaredMethod("setFlags", SurfaceControl.class, int.class, int.class);
                sSetFlags.setAccessible(true);
            }
            sReady = true;
        } catch (Throwable t) {
            Log.e(TAG, "init failed on SDK " + Build.VERSION.SDK_INT, t);
        }
        return sReady;
    }

    /** @return true if the flag was applied to the view's window layer. Call after the window has been laid out. */
    static boolean apply(View view, boolean skip) {
        if (view == null) return false;
        if (!init()) return false;
        try {
            Object root = sGetViewRoot.invoke(view);
            if (root == null) return false;
            SurfaceControl sc = (SurfaceControl) sGetSurfaceControl.invoke(root);
            if (sc == null || !sc.isValid()) return false;

            Object tx = sTxCtor.newInstance();
            try {
                if (sSetSkip != null) {
                    sSetSkip.invoke(tx, sc, skip);
                } else {
                    sSetFlags.invoke(tx, sc, skip ? SKIP_SCREENSHOT : 0, SKIP_SCREENSHOT);
                }
                sApply.invoke(tx);
            } finally {
                try {
                    ((AutoCloseable) tx).close();
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "apply failed on SDK " + Build.VERSION.SDK_INT, t);
            return false;
        }
    }
}
