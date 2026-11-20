package dev.ghostlay.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Full-screen ESP overlay (all except bone). Coordinates come from /api/esp. */
public class EspOverlayView extends View {

    static class Player {
        int id;
        String name = "";
        float hx, hy, rx, ry;
        int hp, maxHp;
        float dist;
        boolean visible;
        boolean bot;
        boolean aimed;   // current aim-touch target
    }

    static class Snapshot {
        int w = 1080, h = 2400;
        int count = 0;
        boolean line, box, health, name, distance, teamId, alert, showCount;
        String linePos = "Top", boxStyle = "Simple", healthStyle = "Vertical";
        int botColor = 0xFF00FF66;
        int playerColor = 0xFFFF3B30;
        int aimColor = 0xFFFFC000;
        int fovColor = 0xFFFFFFFF;
        boolean aimTouch = false;   // draw the FOV circle only while aim touch is on
        float aimFov = 180f;        // aim FOV in game pixels (same units as p[].hx/hy)
        float botDist = 500f, playerDist = 500f;
        final List<Player> players = new ArrayList<>();
    }

    private volatile Snapshot snap = new Snapshot();
    /** False when no usable Typeface exists, in which case every text label is skipped. */
    private final boolean textOk;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint healthBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint healthFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint alertPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fovPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EspOverlayView(Context context) {
        super(context);
        linePaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxFillPaint.setStyle(Paint.Style.FILL);
        healthBg.setStyle(Paint.Style.FILL);
        healthFg.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textBg.setStyle(Paint.Style.FILL);
        textBg.setColor(0x66000000);
        alertPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        alertPaint.setColor(0xFFFF3B30);
        Typeface bold = boldTypeface();
        textOk = bold != null;
        if (bold != null) {
            textPaint.setTypeface(bold);
            alertPaint.setTypeface(bold);
        }
        alertPaint.setTextAlign(Paint.Align.CENTER);
        fovPaint.setStyle(Paint.Style.STROKE);
        fovPaint.setColor(0xFFFFFFFF);
        setWillNotDraw(false);
    }

    /**
     * Typeface.DEFAULT is null when the overlay runs through app_process on a rom
     * whose system Resources never finished loading: MIUI's ThemeCompatibility
     * cannot find /data/system/theme_config/theme_compatibility.xml, MiuiResources
     * comes up half built and Typeface.create(Typeface.DEFAULT, BOLD) throws NPE.
     * Try every base family, then a font file, and give up on text at the end.
     */
    private static Typeface boldTypeface() {
        Typeface[] bases = {Typeface.DEFAULT, Typeface.SANS_SERIF, Typeface.MONOSPACE,
                Typeface.SERIF, Typeface.DEFAULT_BOLD};
        for (Typeface base : bases) {
            if (base == null) continue;
            try {
                Typeface t = Typeface.create(base, Typeface.BOLD);
                if (t != null) return t;
            } catch (Throwable ignored) {
            }
        }
        String[] fonts = {"/system/fonts/MiSans-Bold.ttf", "/system/fonts/Roboto-Bold.ttf",
                "/system/fonts/NotoSans-Bold.ttf", "/system/fonts/RobotoCondensed-Bold.ttf",
                "/system/fonts/MiSans-Regular.ttf", "/system/fonts/Roboto-Regular.ttf",
                "/system/fonts/NotoSans-Regular.ttf"};
        for (String path : fonts) {
            try {
                if (!new java.io.File(path).exists()) continue;
                Typeface t = Typeface.createFromFile(path);
                if (t != null) return t;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int parseColor(String hex, int def) {
        if (hex == null) return def;
        try { return Color.parseColor(hex); } catch (Exception e) { return def; }
    }

    public void updateFromJson(JSONObject root) {
        if (root == null) return;
        try {
            Snapshot s = new Snapshot();
            s.w = Math.max(1, root.optInt("w", 1080));
            s.h = Math.max(1, root.optInt("h", 2400));
            s.count = root.optInt("count", 0);
            JSONObject cfg = root.optJSONObject("cfg");
            if (cfg != null) {
                s.line = cfg.optBoolean("line", false);
                s.box = cfg.optBoolean("box", false);
                s.health = cfg.optBoolean("health", false);
                s.name = cfg.optBoolean("name", false);
                s.distance = cfg.optBoolean("distance", false);
                s.teamId = cfg.optBoolean("teamId", false);
                s.alert = cfg.optBoolean("alert", false);
                s.showCount = cfg.optBoolean("count", false);
                s.linePos = cfg.optString("linePosition", "Top");
                s.boxStyle = cfg.optString("boxStyle", "Simple");
                s.healthStyle = cfg.optString("healthStyle", "Vertical");
                s.botColor = parseColor(cfg.optString("botColor", "#00ff66"), 0xFF00FF66);
                s.playerColor = parseColor(cfg.optString("playerColor", "#ff3b30"), 0xFFFF3B30);
                s.aimColor = parseColor(cfg.optString("aimColor", "#FFC000"), 0xFFFFC000);
                s.fovColor = parseColor(cfg.optString("fovColor", "#FFFFFF"), 0xFFFFFFFF);
                s.aimTouch = cfg.optBoolean("aimTouch", false);
                s.aimFov = (float) cfg.optDouble("aimFov", 180.0);
                s.botDist = (float) cfg.optDouble("botDistance", 500.0);
                s.playerDist = (float) cfg.optDouble("playerDistance", 500.0);
            }
            JSONArray arr = root.optJSONArray("p");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Player p = new Player();
                    p.id = o.optInt("id", i);
                    p.name = o.optString("n", "");
                    p.hx = (float) o.optDouble("hx", 0);
                    p.hy = (float) o.optDouble("hy", 0);
                    p.rx = (float) o.optDouble("rx", 0);
                    p.ry = (float) o.optDouble("ry", 0);
                    p.hp = o.optInt("hp", 0);
                    p.maxHp = o.optInt("mx", 0);
                    p.dist = (float) o.optDouble("d", 0);
                    p.visible = o.optBoolean("v", false);
                    p.bot = o.optBoolean("b", p.name.isEmpty());
                    p.aimed = o.optBoolean("a", false);
                    s.players.add(p);
                }
            }
            snap = s;
            postInvalidateOnAnimation();
        } catch (Exception ignored) {
        }
    }

    public void clear() {
        snap = new Snapshot();
        postInvalidateOnAnimation();
    }

    private float dp(float v) {
        float density = 1f;
        try {
            density = getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
            // half-initialised MiuiResources: fall back to 1x instead of drawing nothing
        }
        if (density <= 0f) density = 1f;
        return v * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Snapshot s = snap;
        if (s == null) return;

        float vw = getWidth();
        float vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        float sx = vw / (float) Math.max(1, s.w);
        float sy = vh / (float) Math.max(1, s.h);

        float stroke = Math.max(1.5f, dp(1.5f));
        float textSize = dp(10);
        textPaint.setTextSize(textSize);
        alertPaint.setTextSize(dp(14));
        linePaint.setStrokeWidth(stroke);
        boxPaint.setStrokeWidth(stroke);

        // FOV circle: shown only while Aim Touch is enabled, radius follows the
        // Aim FOV setting (converted from game pixels to view pixels).
        if (s.aimTouch && s.aimFov > 1f) {
            fovPaint.setColor(s.fovColor);
            fovPaint.setStrokeWidth(Math.max(1f, dp(1.2f)));
            canvas.drawCircle(vw / 2f, vh / 2f, s.aimFov * sx, fovPaint);
        }

        int drawn = 0;
        boolean alertTrig = false;

        for (Player p : s.players) {
            int color = p.aimed ? s.aimColor : (p.bot ? s.botColor : s.playerColor);
            float maxDist = p.bot ? s.botDist : s.playerDist;
            if (maxDist > 0 && p.dist > maxDist) continue;

            float hx = p.hx * sx, hy = p.hy * sy;
            float rx = p.rx * sx, ry = p.ry * sy;

            float top = Math.min(hy, ry);
            float bottom = Math.max(hy, ry);
            float height = Math.max(dp(20), bottom - top);
            top = Math.min(top, hy - height * 0.15f);
            bottom = Math.max(bottom, ry);
            height = bottom - top;
            float width = Math.max(dp(18), height * 0.45f);
            float cx = (hx + rx) * 0.5f;
            float left = cx - width / 2f;
            float right = cx + width / 2f;

            drawn++;
            if (p.visible) alertTrig = true;

            if (s.line) {
                float lx, ly;
                if ("Bottom".equalsIgnoreCase(s.linePos)) { lx = vw / 2f; ly = vh; }
                else if ("Middle".equalsIgnoreCase(s.linePos)) { lx = vw / 2f; ly = vh / 2f; }
                else { lx = vw / 2f; ly = 0f; }
                linePaint.setColor(color);
                canvas.drawLine(lx, ly, cx, top, linePaint);
            }

            if (s.box) {
                boxPaint.setColor(color);
                if ("Corner".equalsIgnoreCase(s.boxStyle)) {
                    float cl = Math.min(width, height) * 0.25f;
                    canvas.drawLine(left, top, left + cl, top, boxPaint);
                    canvas.drawLine(left, top, left, top + cl, boxPaint);
                    canvas.drawLine(right, top, right - cl, top, boxPaint);
                    canvas.drawLine(right, top, right, top + cl, boxPaint);
                    canvas.drawLine(left, bottom, left + cl, bottom, boxPaint);
                    canvas.drawLine(left, bottom, left, bottom - cl, boxPaint);
                    canvas.drawLine(right, bottom, right - cl, bottom, boxPaint);
                    canvas.drawLine(right, bottom, right, bottom - cl, boxPaint);
                } else if ("Filled".equalsIgnoreCase(s.boxStyle)) {
                    boxFillPaint.setColor((color & 0x00FFFFFF) | 0x33000000);
                    canvas.drawRect(left, top, right, bottom, boxFillPaint);
                    canvas.drawRect(left, top, right, bottom, boxPaint);
                } else {
                    canvas.drawRect(left, top, right, bottom, boxPaint);
                }
            }

            if (s.health && p.maxHp > 0) {
                float ratio = Math.max(0f, Math.min(1f, (float) p.hp / (float) p.maxHp));
                int hpColor = ratio > 0.6f ? 0xFF34C759 : (ratio > 0.3f ? 0xFFFFCC00 : 0xFFFF3B30);
                if ("Horizontal".equalsIgnoreCase(s.healthStyle)) {
                    float barH = Math.max(dp(3), stroke * 1.5f);
                    float y = bottom + dp(2);
                    healthBg.setColor(0xAA000000);
                    healthFg.setColor(hpColor);
                    canvas.drawRect(left, y, right, y + barH, healthBg);
                    canvas.drawRect(left, y, left + (right - left) * ratio, y + barH, healthFg);
                } else {
                    float barW = Math.max(dp(3), stroke * 1.5f);
                    float x = left - barW - dp(2);
                    healthBg.setColor(0xAA000000);
                    healthFg.setColor(hpColor);
                    canvas.drawRect(x, top, x + barW, bottom, healthBg);
                    float filledTop = bottom - (bottom - top) * ratio;
                    canvas.drawRect(x, filledTop, x + barW, bottom, healthFg);
                }
            }

            float textY = top - dp(3);
            if (textOk && (s.name || s.teamId || s.distance)) {
                StringBuilder sb = new StringBuilder();
                if (s.name) {
                    String nm = (p.name != null && !p.name.isEmpty()) ? p.name : (p.bot ? "BOT" : "Player");
                    sb.append(nm);
                }
                if (s.teamId) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append("[").append(p.id).append("]");
                }
                if (s.distance) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append((int) p.dist).append("m");
                }
                String label = sb.toString();
                if (!label.isEmpty()) {
                    float tw = textPaint.measureText(label);
                    float tx = cx - tw / 2f;
                    float pad = dp(2);
                    textPaint.setColor(Color.WHITE);
                    canvas.drawRect(tx - pad, textY - textSize, tx + tw + pad, textY + pad, textBg);
                    canvas.drawText(label, tx, textY, textPaint);
                }
            }
        }

        if (textOk && s.showCount) {
            String label = "Enemies: " + s.count;
            float tw = textPaint.measureText(label);
            float pad = dp(4);
            float bx = vw / 2f - tw / 2f;
            float by = dp(22);
            textPaint.setColor(Color.WHITE);
            canvas.drawRect(bx - pad, by - textSize, bx + tw + pad, by + pad, textBg);
            canvas.drawText(label, bx, by, textPaint);
        }

        if (textOk && s.alert && alertTrig && drawn > 0) {
            alertPaint.setColor(0xFFFF3B30);
            canvas.drawText("ALERT", vw / 2f, dp(42), alertPaint);
        }
    }
}
