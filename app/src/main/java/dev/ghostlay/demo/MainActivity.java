package dev.ghostlay.demo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setText("Ghostlay ESP: the module starts this automatically at boot");
        status.setGravity(Gravity.CENTER);

        Button start = new Button(this);
        start.setText("Grant (root) & start overlay");
        start.setOnClickListener(v -> start());

        Button stop = new Button(this);
        stop.setText("Stop overlay");
        stop.setOnClickListener(v ->
                startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP)));

        root.addView(status);
        root.addView(start);
        root.addView(stop);
        setContentView(root);
    }

    private void start() {
        status.setText("Requesting root…");
        new Thread(() -> {
            boolean root = RootUtil.hasRoot();
            if (root) RootUtil.grantPermissions(getPackageName());
            runOnUiThread(() -> afterGrant(root));
        }).start();
    }

    private void afterGrant(boolean root) {
        if (!Settings.canDrawOverlays(this)) {
            status.setText((root ? "Root OK, but overlay permission is still off." : "No root.")
                    + " Enable \"Display over other apps\", then press Start again.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        startForegroundService(new Intent(this, OverlayService.class));
        status.setText("Running. The ESP overlay is drawn over the game.");
    }
}
