package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.Button;

import java.io.File;

/**
 * The setup screen's look, taken from the user's own install (logo, menu buttons, vignette, Alegreya) so it
 * matches the game. None of that art ships in the APK; before the game is installed a plain look stands in.
 */
final class WildermythTheme {
    static final int BG = Color.rgb(21, 16, 12);
    static final int TEXT = Color.rgb(240, 226, 200);
    static final int ACCENT = Color.rgb(222, 158, 74);

    private final Context ctx;
    final Typeface font, fontBold;
    final Bitmap logo, vignette;
    private final Bitmap buttonUp, buttonOver, buttonDown;
    /** Drawn once per state; every screen change builds new buttons. */
    private final Bitmap[] parchment = new Bitmap[3];

    WildermythTheme(Context ctx, File game) {
        this.ctx = ctx;
        File a = new File(game, "assets");
        // The game's fonts when installed; otherwise the app's own copy of Alegreya (SIL OFL, see assets/fonts).
        Typeface bundled = Typeface.createFromAsset(ctx.getAssets(), "fonts/Alegreya.ttf");
        Typeface bundledBold = android.os.Build.VERSION.SDK_INT >= 28 ? Typeface.create(bundled, 700, false) : Typeface.create(bundled, Typeface.BOLD);
        font = typeface(new File(a, "fonts/Alegreya-Regular.ttf"), bundled);
        fontBold = typeface(new File(a, "fonts/Alegreya-Bold.ttf"), bundledBold);
        logo = bitmap(new File(a, "menu/logoWildermyth_dark.png"));
        vignette = bitmap(new File(a, "menu/edgeModal.png"));
        File ui = new File(a, "ui/scaleUI/unpacked");
        buttonUp = bitmap(new File(ui, "buttonMainMenu_512x120_up.png"));
        buttonOver = bitmap(new File(ui, "buttonMainMenu_512x120_over.png"));
        buttonDown = bitmap(new File(ui, "buttonMainMenu_512x120_down.png"));
    }

    /** A menu button like the game's: parchment art when available, lighter when focused by the pad. */
    void style(Button b) {
        b.setAllCaps(false);
        b.setTypeface(fontBold);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Color.WHITE, TEXT}));
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, face(buttonDown, 0));
        d.addState(new int[]{android.R.attr.state_focused}, focused(face(buttonOver, 1)));
        d.addState(new int[]{}, face(buttonUp, 2));
        b.setBackground(d);
        b.setStateListAnimator(null);
        b.setPadding(dp(32), dp(10), dp(32), dp(10));
        // The game's "over" art barely differs from "up"; on a pad the focus must be obvious.
        b.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start());
    }

    private Drawable focused(Drawable face) {
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(dp(8));
        ring.setStroke(dp(3), ACCENT);
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{face, ring});
    }

    /** Warm glow fading to the dark edges, like the game's menu backdrop. */
    Drawable background() {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{Color.rgb(64, 43, 27), BG});
        g.setGradientRadius(ctx.getResources().getDisplayMetrics().widthPixels * 0.6f);
        return g;
    }

    /** Rounded panel behind the QR code, which needs a light, quiet border to scan reliably. */
    Drawable card() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(12));
        return g;
    }

    int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** The game's art for a button state, or our parchment in its place: pressed, focused, idle. */
    private Drawable face(Bitmap art, int state) {
        if (art != null) return new BitmapDrawable(ctx.getResources(), art);
        if (parchment[state] == null) // paper, not bark: the fills are lifted from the game's button browns
            parchment[state] = parchment(lighten(new int[]{0xFF5A3B22, 0xFF6E4A2A, 0xFF3A281A}[state], 2.1f));
        return new BitmapDrawable(ctx.getResources(), parchment[state]);
    }

    /** Our own parchment strip: speckled paper with rust bands near each end, echoing the game's buttons. */
    private static Bitmap parchment(int base) {
        int w = 512, h = 120;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        java.util.Random rnd = new java.util.Random(7); // fixed seed: the same texture every time
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float grain = 0.9f + rnd.nextFloat() * 0.2f - (Math.abs(y - h / 2f) / h) * 0.25f;
                int c = lighten(base, grain);
                boolean band = (x > 18 && x < 30) || (x > 36 && x < 42) || (x > w - 30 && x < w - 18) || (x > w - 42 && x < w - 36);
                if (band) c = lighten(Color.rgb(150, 70, 40), grain);
                boolean edge = x < 6 || x > w - 7 || y < 4 || y > h - 5;
                px[y * w + x] = edge && rnd.nextFloat() < 0.6f ? Color.TRANSPARENT : c;
            }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static int lighten(int c, float f) {
        return Color.rgb(Math.min(255, (int) (Color.red(c) * f)), Math.min(255, (int) (Color.green(c) * f)), Math.min(255, (int) (Color.blue(c) * f)));
    }

    private static Typeface typeface(File f, Typeface fallback) {
        try {
            return f.isFile() ? Typeface.createFromFile(f) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Bitmap bitmap(File f) {
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }
}
