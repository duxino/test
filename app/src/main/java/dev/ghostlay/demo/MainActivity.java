package dev.ghostlay.demo;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.Display;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String CONFIG_URL = "http://127.0.0.1:6969/api/config";
    private static final int BG = 0xFF0E0B16;
    private static final int FG = 0xFFEDE7F6;
    private static final int ACCENT = 0xFFB388FF;

    private TextView rootText;
    private TextView serverText;
    private TextView infoText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        getWindow().setFlags(0, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(pad, pad, pad, pad);

        TextView title = text("Ghostlay", 26, FG);
        TextView sub = text("ESP Line / Box overlay, hidden from screen recorders", 13, 0xFF9E93B8);
        sub.setPadding(0, (int) (6 * d), 0, (int) (16 * d));

        rootText = text("Root: not checked yet", 13, ACCENT);
        serverText = text("TX9 server: checking ...", 13, ACCENT);
        infoText = text("", 11, 0xFF7E7590);
        statusText = text("Press \"Request root\" first, then \"Start overlay\".", 13, FG);
        statusText.setPadding(0, (int) (12 * d), 0, (int) (16 * d));

        Button rootBtn = button("1) Request root (su)");
        rootBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkRoot();
            }
        });

        Button startBtn = button("2) Start overlay");
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFlow();
            }
        });

        Button stopBtn = button("Stop overlay");
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService(new Intent(MainActivity.this, OverlayService.class)
                        .setAction(OverlayService.ACTION_STOP));
            }
        });

        Button serverBtn = button("Check server again");
        serverBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkServer();
            }
        });

        column.addView(title);
        column.addView(sub);
        column.addView(rootText);
        column.addView(serverText);
        column.addView(infoText);
        column.addView(statusText);
        column.addView(rootBtn);
        column.addView(startBtn);
        column.addView(stopBtn);
        column.addView(serverBtn);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        showSizes();
        forceFullDisplayIfShrunk();
    }

    private static boolean sRelaunched = false;

    /**
     * Some devices/ROMs open the app in a small freeform window (rest of the screen black).
     * Relaunch once with launch bounds covering the whole display to force full screen.
     */
    private void forceFullDisplayIfShrunk() {
        if (Build.VERSION.SDK_INT < 24 || sRelaunched) return;
        try {
            Display display = getWindowManager().getDefaultDisplay();
            Point real = new Point();
            display.getRealSize(real);
            int sw = real.x;
            int sh = real.y;
            int ww;
            int wh;
            if (Build.VERSION.SDK_INT >= 30) {
                Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
                ww = b.width();
                wh = b.height();
            } else {
                Point sz = new Point();
                display.getSize(sz);
                ww = sz.x;
                wh = sz.y;
            }
            Log.i("Ghostlay", "window " + ww + "x" + wh + " display " + sw + "x" + sh);
            if (ww >= sw * 0.9f && wh >= sh * 0.9f) return;

            sRelaunched = true;
            Intent again = new Intent(this, MainActivity.class);
            again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            Bundle opts = ActivityOptions.makeBasic()
                    .setLaunchBounds(new Rect(0, 0, sw, sh))
                    .toBundle();
            startActivity(again, opts);
            finish();
        } catch (Throwable t) {
            Log.w("Ghostlay", "full-display relaunch failed: " + t);
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    /** Helps spot device display overrides (wm size / density) that shrink the app window. */
    private void showSizes() {
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Point real = new Point();
                    getWindowManager().getDefaultDisplay().getRealSize(real);
                    int w = getWindow().getDecorView().getWidth();
                    int h = getWindow().getDecorView().getHeight();
                    float dens = getResources().getDisplayMetrics().density;
                    infoText.setText("screen " + real.x + "x" + real.y
                            + "  |  window " + w + "x" + h
                            + "  |  density " + String.format("%.2f", dens));
                } catch (Throwable t) {
                    infoText.setText("size info unavailable");
                }
            }
        });
    }

    private void checkRoot() {
        rootText.setText("Root: waiting for su prompt ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String st = RootUtil.status();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        rootText.setText("Root: " + st);
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkServer();
    }

    private void checkServer() {
        serverText.setText("TX9 server: checking ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = probeServer();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        serverText.setText(text);
                    }
                });
            }
        }).start();
    }

    private String probeServer() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(CONFIG_URL).openConnection();
            conn.setConnectTimeout(700);
            conn.setReadTimeout(700);
            conn.setUseCaches(false);
            if (conn.getResponseCode() != 200) {
                return "TX9 server: HTTP " + conn.getResponseCode();
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
            br.close();
            JSONObject esp = new JSONObject(sb.toString()).optJSONObject("esp");
            if (esp == null) return "TX9 server: connected";
            return "TX9 server: connected  |  line=" + (esp.optBoolean("line") ? "on" : "off")
                    + "  box=" + (esp.optBoolean("box") ? "on" : "off")
                    + "  color=" + esp.optString("playerColor", "-");
        } catch (Exception e) {
            return "TX9 server: offline (start modmenu_server first)";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void startFlow() {
        statusText.setText("Checking root ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean root = RootUtil.hasRoot();
                if (root) {
                    RootUtil.grantPermissions(getPackageName());
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        rootText.setText("Root: " + (root ? "granted" : RootUtil.lastError()));
                        afterGrant(root);
                    }
                });
            }
        }).start();
    }

    private void afterGrant(boolean root) {
        if (!Settings.canDrawOverlays(this)) {
            statusText.setText("Overlay permission is off. Turning it on"
                    + (root ? " via root (may need a second, then press Start again)…" : " in Settings…"));
            if (root) {
                RootUtil.su("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            }
            if (Settings.canDrawOverlays(this)) {
                statusText.setText("Overlay permission granted. Press Start overlay again.");
                return;
            }
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(new Intent(this, OverlayService.class));
        } else {
            startService(new Intent(this, OverlayService.class));
        }
        statusText.setText("Overlay started. It follows the TX9 menu within ~0.7s.");
        checkServer();
    }
}
