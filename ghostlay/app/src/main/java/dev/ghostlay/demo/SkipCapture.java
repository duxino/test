package dev.ghostlay.demo;

import android.os.Build;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Marks a window's layer with SurfaceControl's SKIP_SCREENSHOT flag.
 *
 * SurfaceFlinger leaves such a layer out of screenshots, display mirroring and
 * screen recording (anything rendered to a non-primary display), while the
 * physical screen still shows it. Not black, not blurred: the layer is simply absent
 * from the capture. AOSP uses the same flag for the mouse-pointer sprite.
 *
 * Nothing is persisted. The flag lives on the layer and dies with the window.
 */
final class SkipCapture {
    private static final String TAG = "Ghostlay";
    private static final int SKIP_SCREENSHOT = 0x00000040; // SurfaceControl.SKIP_SCREENSHOT (@hide)

    private static boolean sTried, sReady;
    private static Method sGetViewRoot, sGetSurfaceControl, sSetSkip, sSetFlags, sApply;
    private static Constructor<?> sTxCtor;

    private SkipCapture() {}

    private static synchronized boolean init() {
        if (sTried) return sReady;
        sTried = true;
        try {
            // Exempt only android.view.*, in this process only.
            HiddenApiBypass.addHiddenApiExemptions("Landroid/view/");

            sGetViewRoot = View.class.getDeclaredMethod("getViewRootImpl");
            Class<?> viewRoot = Class.forName("android.view.ViewRootImpl");
            sGetSurfaceControl = viewRoot.getDeclaredMethod("getSurfaceControl");

            Class<?> tx = Class.forName("android.view.SurfaceControl$Transaction");
            sTxCtor = tx.getDeclaredConstructor();
            sApply = tx.getDeclaredMethod("apply");

            try {
                sSetSkip = tx.getDeclaredMethod("setSkipScreenshot", SurfaceControl.class, boolean.class);
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "setSkipScreenshot missing on SDK " + Build.VERSION.SDK_INT + ", using setFlags");
                sSetFlags = tx.getDeclaredMethod("setFlags", SurfaceControl.class, int.class, int.class);
            }
            sReady = true;
        } catch (Throwable t) {
            Log.e(TAG, "init failed on SDK " + Build.VERSION.SDK_INT, t);
        }
        return sReady;
    }

    /** @return true if the flag was applied to the view's window layer. Call after the window has been laid out. */
    static boolean apply(View view, boolean skip) {
        if (!init()) return false;
        try {
            Object root = sGetViewRoot.invoke(view);
            if (root == null) return false;
            SurfaceControl sc = (SurfaceControl) sGetSurfaceControl.invoke(root);
            if (sc == null || !sc.isValid()) return false;

            Object tx = sTxCtor.newInstance();
            try {
                if (sSetSkip != null) sSetSkip.invoke(tx, sc, skip);
                else sSetFlags.invoke(tx, sc, skip ? SKIP_SCREENSHOT : 0, SKIP_SCREENSHOT);
                sApply.invoke(tx);
            } finally {
                ((AutoCloseable) tx).close();
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "apply failed on SDK " + Build.VERSION.SDK_INT, t);
            return false;
        }
    }
}
