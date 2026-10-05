package net.kdt.pojavlaunch;

import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.content.res.ColorStateList;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wmcloud.CloudChangedException;
import wmcloud.ConflictException;
import wmcloud.WmCloud;

/**
 * The app's front door: install game files, sign in to Steam, pull saves, run the game, then push saves
 * and achievements. A "session active" flag survives crashes, so an interrupted session still uploads.
 */
public class WildermythActivity extends AppCompatActivity {
    private static final int PICK_GAME_DIR = 1;
    private static final String PREF_SESSION = "wm_session_active";
    /** Debug: fetch one small file from Steam into the cache and report, touching nothing else. */
    static final String EXTRA_TEST_DOWNLOAD = "wm_test_download";
    /** Debug: draw the screen with sample content only, to check the look. */
    static final String EXTRA_PREVIEW = "wm_preview";
    /** Straight from opening the app into the game, skipping the Ready screen. */
    private static final String PREF_AUTO_LAUNCH = "wm_auto_launch";
    /** Which screen the preview draws: synced (default), firstrun, conflict. */
    static final String EXTRA_PREVIEW_SCREEN = "wm_preview_screen";
    /** Debug: start the installed game with no Steam sign-in or sync, for testing without an account. */
    static final String EXTRA_PLAY_NO_SYNC = "wm_play_no_sync";
    /** Debug: offer the latest release as an update even if it isn't newer. */
    static final String EXTRA_UPDATE_TEST = "wm_update_test";
    private boolean testing;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private TextView heading, status, footer, more;
    private ImageView morePrompt;
    /** The buttons and the Settings link: under the message, or alone on the bottom screen in dual-screen mode. */
    private LinearLayout controls, column;
    private android.app.Presentation bottom;
    private boolean inSettings, started, confirmDown;
    /** Settings' Back, for the pad's back button too. */
    private Runnable leaveSettings;
    /** A newer release, once the background check finds one. */
    private WildermythUpdater.Update available;
    /** Re-shows the current screen; the update flow returns there on "Not now". */
    private Runnable screen;
    private String screenHeading;
    private boolean updating;
    private ImageView qr;
    private ProgressBar progress;
    private LinearLayout buttons;
    private File game;
    private WildermythTheme theme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ignoreJavaSteamCancellations();
        prefs = getSharedPreferences("wildermyth", MODE_PRIVATE);
        game = WildermythLauncher.gameDir(this);
        WmCloud.configure(new File(getFilesDir(), "wmcloud"), line -> runOnUiThread(() -> status.setText(line)));

        buildUi();
        hideSystemBars();
        // Test and preview switches exist only in debuggable builds; release ignores them.
        boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable && getIntent().getBooleanExtra(EXTRA_TEST_DOWNLOAD, false)) { testDownload(); return; }
        WildermythUpdater.pretendOld = debuggable && getIntent().getBooleanExtra(EXTRA_UPDATE_TEST, false);
        if (debuggable && getIntent().getBooleanExtra(EXTRA_PLAY_NO_SYNC, false)) {
            testing = true;
            startActivity(new Intent(this, MainActivity.class).putExtra(WildermythLauncher.EXTRA, true));
            return;
        }
        checkForUpdate();
        if (debuggable && getIntent().getBooleanExtra(EXTRA_PREVIEW, false)) { testing = true; preview(getIntent().getStringExtra(EXTRA_PREVIEW_SCREEN)); return; }
        if (!prefs.getBoolean(PREF_SESSION, false)) next();
    }

    /** In the background, so play never waits on GitHub; a find shows on the Settings link. */
    private void checkForUpdate() {
        if (!WildermythUpdater.due(this)) return;
        new Thread(() -> {
            try {
                WildermythUpdater.Update u = WildermythUpdater.check(this);
                runOnUiThread(() -> { available = u; refreshMore(); });
            } catch (Exception e) {
                android.util.Log.w("Wildermyth", "update check failed", e); // offline is fine: play anyway
            }
        }, "wm-update-check").start();
    }

    /** The Settings link, only while the screen waits on the player; it flags an update. */
    private void refreshMore() {
        boolean idle = buttons.getChildCount() > 0 && !inSettings && !updating;
        more.setVisibility(idle ? View.VISIBLE : View.GONE);
        ((View) more.getParent()).setVisibility(idle ? View.VISIBLE : View.GONE);
        more.setText(available == null ? "Settings" : "Settings  ·  Update available");
    }

    /** Version, update, and the two switches; Back returns to the screen it was opened from. */
    private void showSettings(Runnable back) {
        showSettings(back, 0);
    }

    /** {@code focus}: the button to leave the pad on, so flipping a switch keeps your place. */
    private void showSettings(Runnable back, int focus) {
        long code = 0;
        try {
            code = WildermythUpdater.installedVersionCode(this);
        } catch (Exception ignored) {
            // the line just loses its number
        }
        inSettings = true;
        show("Version " + WildermythUpdater.tag(code) + (available == null ? "" : ". " + available.version + " is out."));
        heading("Settings");
        if (available != null) addButton("Update to " + available.version, () -> {
            inSettings = false;
            updating = true;
            update(available, () -> {
                updating = false;
                back.run();
            });
        });
        boolean ds = WildermythSecondScreen.display(this) != null;
        int dsAt = buttons.getChildCount();
        if (ds) addButton(switchLabel("Dual screen", WildermythSecondScreen.PREF, false), () -> {
            flip(WildermythSecondScreen.PREF, false);
            placeControls();
            showSettings(back, dsAt);
        });
        int autoAt = buttons.getChildCount();
        addButton(switchLabel("Auto launch", PREF_AUTO_LAUNCH, false), () -> {
            flip(PREF_AUTO_LAUNCH, false);
            showSettings(back, autoAt);
        });
        int updatesAt = buttons.getChildCount();
        addButton(switchLabel("Updates", WildermythUpdater.PREF, true), () -> {
            flip(WildermythUpdater.PREF, true);
            showSettings(back, updatesAt);
        });
        leaveSettings = () -> {
            inSettings = false;
            back.run();
            focus(more); // back where Settings was opened from
        };
        addButton("Back", leaveSettings);
        theme.backButton((Button) buttons.getChildAt(buttons.getChildCount() - 1));
        focus(buttons.getChildAt(Math.min(focus, buttons.getChildCount() - 1)));
        refreshMore();
    }

    private String switchLabel(String name, String pref, boolean byDefault) {
        return name + ": " + (prefs.getBoolean(pref, byDefault) ? "On" : "Off");
    }

    /** Saved at once; each is read when it next matters (game launch, update check). */
    private void flip(String pref, boolean byDefault) {
        prefs.edit().putBoolean(pref, !prefs.getBoolean(pref, byDefault)).apply();
    }

    /** Settings from whatever screen is up, coming back to it after. */
    private void openSettings() {
        Runnable back = screen;
        String h = screenHeading;
        showSettings(() -> {
            back.run();
            if (h != null) heading(h);
        });
    }

    /** In dual-screen mode the controls go to the bottom screen, for touch; the pad still drives them. */
    private void placeControls() {
        android.view.Display d = WildermythSecondScreen.display(this);
        boolean ds = d != null && started && prefs.getBoolean(WildermythSecondScreen.PREF, false);
        if (controls.getParent() != null) ((android.view.ViewGroup) controls.getParent()).removeView(controls);
        if (!ds && bottom != null) {
            bottom.dismiss();
            bottom = null;
        }
        if (ds && bottom == null) {
            bottom = new android.app.Presentation(this, d);
            FrameLayout root = new FrameLayout(bottom.getContext());
            root.setBackground(theme.background());
            bottom.setContentView(root);
            bottom.show();
        }
        if (ds) bottomRoot().addView(controls, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        else column.addView(controls);
        orientButtons();
    }

    private void focusFirst() {
        focus(buttons.getChildAt(0));
    }

    private void focus(View v) {
        if (bottom != null) v.requestFocusFromTouch(); // see dispatchKeyEvent
        else v.requestFocus();
    }

    private FrameLayout bottomRoot() {
        return (FrameLayout) ((android.view.ViewGroup) bottom.findViewById(android.R.id.content)).getChildAt(0);
    }

    /** Side by side on the wide top screen; stacked on the small bottom one, and in Settings. */
    private void orientButtons() {
        buttons.setOrientation(bottom != null || inSettings ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
    }


    /** Asks for "Install unknown apps" first if Android needs it, then hands over to its installer. */
    private void installUpdate(java.io.File apk, WildermythUpdater.Update u, Runnable then) {
        if (!WildermythUpdater.mayInstall(this)) {
            show("To update, allow this app to install apps: switch it on in the page that opens, then come back.",
                    "Open settings", () -> {
                        WildermythUpdater.askToInstall(this);
                        show("Once it's switched on, install the update.", "Install update", () -> installUpdate(apk, u, then), "Not now", then);
                    }, "Not now", then);
            return;
        }
        show("Confirm the update in the window Android shows.", "Not now", then);
        try {
            WildermythUpdater.install(this, apk, status -> runOnUiThread(() ->
                    show(WildermythUpdater.why(status), "Try again", () -> installUpdate(apk, u, then), "Not now", then)));
        } catch (Exception e) {
            show("The update didn't install: " + describe(e), "Try again", () -> update(u, then), "Not now", then);
        }
    }

    private void update(WildermythUpdater.Update u, Runnable then) {
        show("Downloading " + u.version + "…");
        progress(0);
        transfer(true);
        worker.execute(() -> {
            try {
                java.io.File apk = WildermythUpdater.download(this, u, pct -> runOnUiThread(() -> progress(pct)));
                runOnUiThread(() -> {
                    transfer(false);
                    installUpdate(apk, u, then);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    transfer(false);
                    show("The download failed: " + describe(e), "Try again", () -> update(u, then), "Not now", then);
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (testing) return;
        // Back from the game (or relaunched after it crashed): upload before anything else.
        if (prefs.getBoolean(PREF_SESSION, false) && !gameRunning()) afterSession();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (inSettings) leaveSettings.run();
        else super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (controls != null) placeControls();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false; // the game's own panel takes the bottom screen
        if (controls != null) placeControls();
    }

    /**
     * On the bottom screen the controls sit in a window without key focus, so the pad's keys never reach
     * them: move focus and press buttons there by hand.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (bottom == null) return super.dispatchKeyEvent(e);
        int step;
        switch (e.getKeyCode()) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: step = -1; break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: step = 1; break;
            case android.view.KeyEvent.KEYCODE_BUTTON_A:
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER: step = 0; break;
            default: return super.dispatchKeyEvent(e);
        }
        // The controls are one stacked column there: walk it in order.
        List<View> order = new java.util.ArrayList<>();
        for (int i = 0; i < buttons.getChildCount(); i++) order.add(buttons.getChildAt(i));
        if (more.getVisibility() == View.VISIBLE) order.add(more);
        int at = order.indexOf(controls.findFocus());
        if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) {
            // Only a press that started here: the A that opened the app from a frontend lets go in this window.
            if (step == 0 && confirmDown && !order.isEmpty()) order.get(Math.max(at, 0)).performClick(); // unfocused: the first
            if (step == 0) confirmDown = false;
            return true;
        }
        if (step == 0) confirmDown = true;
        if (order.isEmpty()) return true;
        int next = at < 0 ? 0 : Math.max(0, Math.min(order.size() - 1, at + step));
        // FromTouch: that window stays in touch mode, where a plain requestFocus is refused.
        if (next != at) order.get(next).requestFocusFromTouch();
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars(); // bars come back after the file picker and the game
    }

    /** Fullscreen like the game; a swipe from the edge shows the bars briefly. */
    private void hideSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat c =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    /** Lays the screen out in the game's style; rebuilt once game files (and so the art) arrive. */
    private void buildUi() {
        theme = new WildermythTheme(this, game);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(theme.background());
        if (theme.vignette != null) {
            ImageView v = new ImageView(this);
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            v.setImageBitmap(theme.vignette);
            root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        // Bottom padding keeps the last button clear of the footer on the handheld's short screen.
        col.setPadding(theme.dp(24), theme.dp(8), theme.dp(24), theme.dp(32));
        if (theme.logo != null) {
            ImageView logo = new ImageView(this);
            logo.setImageBitmap(theme.logo);
            logo.setAdjustViewBounds(true);
            col.addView(logo, new LinearLayout.LayoutParams(theme.dp(280), -2));
        } else {
            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER);
            ImageView fire = new ImageView(this); // the app icon's campfire: original art, safe to ship
            fire.setImageResource(R.drawable.wm_icon_foreground);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(theme.dp(96), theme.dp(96));
            fp.rightMargin = -theme.dp(14); // the vector keeps a safe-zone margin; pull the title in
            header.addView(fire, fp);
            TextView t = text("Wildermyth", 48);
            t.setTypeface(theme.fontBold);
            t.setTextColor(WildermythTheme.ACCENT);
            t.setLetterSpacing(0.04f);
            t.setShadowLayer(theme.dp(8), 0, theme.dp(2), 0xAA000000);
            header.addView(t);
            // Same width as the campfire on the right, so the title (not title + icon) is centred.
            header.addView(new View(this), new LinearLayout.LayoutParams(theme.dp(96 - 14), 1));
            col.addView(header);
        }
        heading = text("", 24);
        heading.setTypeface(theme.fontBold);
        heading.setTextColor(WildermythTheme.ACCENT);
        heading.setVisibility(View.GONE);
        col.addView(heading);
        status = text("", 19);
        status.setMaxWidth(theme.dp(760));
        col.addView(status, new LinearLayout.LayoutParams(theme.dp(760), -2));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(WildermythTheme.ACCENT));
        progress.setVisibility(View.GONE);
        col.addView(progress, new LinearLayout.LayoutParams(theme.dp(460), theme.dp(10)));
        qr = new ImageView(this);
        qr.setBackground(theme.card());
        qr.setPadding(theme.dp(10), theme.dp(10), theme.dp(10), theme.dp(10));
        qr.setVisibility(View.GONE);
        col.addView(qr, new LinearLayout.LayoutParams(theme.dp(230), theme.dp(230)));
        buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        buttons.setPadding(0, theme.dp(12), 0, 0);
        more = text("Settings", 16);
        int pad = theme.dp(12);
        more.setPadding(pad, pad, pad, pad);
        more.setFocusable(true);
        more.setOnClickListener(v -> openSettings());
        if (android.os.Build.VERSION.SDK_INT >= 26) more.setDefaultFocusHighlightEnabled(false); // gold text + A show focus
        more.setOnFocusChangeListener((v, has) -> {
            more.setTextColor(has ? WildermythTheme.ACCENT : WildermythTheme.TEXT);
            morePrompt.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        if (controls != null && controls.getParent() != null) ((android.view.ViewGroup) controls.getParent()).removeView(controls);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.addView(buttons);
        // The link centred between an empty slot and the A prompt's, so the prompt never shifts the words.
        LinearLayout moreRow = new LinearLayout(this);
        moreRow.setGravity(Gravity.CENTER);
        int prompt = theme.dp(26), gap = 0; // the link's own padding is the gap
        moreRow.addView(new View(this), new LinearLayout.LayoutParams(prompt + gap, prompt));
        moreRow.addView(more, new LinearLayout.LayoutParams(-2, -2));
        morePrompt = new ImageView(this);
        morePrompt.setImageDrawable(theme.promptA());
        morePrompt.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(prompt, prompt);
        pp.leftMargin = gap;
        moreRow.addView(morePrompt, pp);
        controls.addView(moreRow);
        column = col;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // With no buttons on screen the pad would focus the scroller and grey out the whole screen.
        scroll.setFocusable(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            scroll.setDefaultFocusHighlightEnabled(false);
            root.setDefaultFocusHighlightEnabled(false);
        }
        FrameLayout center = new FrameLayout(this);
        center.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        scroll.addView(center);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        footer = text("", 13);
        footer.setAlpha(0.6f);
        root.addView(footer, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        updateFooter();
        placeControls();
        refreshMore();
    }

    private void updateFooter() {
        String account = WmCloud.accountName();
        // FMOD's licence requires this credit line in the app.
        footer.setText((account == null ? "Not signed in" : "Steam: " + account)
                + "  ·  Audio: FMOD Studio by Firelight Technologies Pty Ltd.");
    }

    /** While copying or downloading: screen stays on, and a foreground service keeps going if it is turned off. */
    private void transfer(boolean on) {
        if (on) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            net.kdt.pojavlaunch.services.WildermythTransferService.start(this);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            net.kdt.pojavlaunch.services.WildermythTransferService.stop(this);
        }
    }

    /** Determinate progress, 0..100; negative hides the bar. */
    private void progress(float pct) {
        progress.setVisibility(pct < 0 ? View.GONE : View.VISIBLE);
        if (pct >= 0) progress.setProgress(Math.round(pct * 10));
    }

    /** Walks the setup steps in order; each one calls back here when done. */
    private void next() {
        if (theme == null || (theme.logo == null && new File(game, "assets").isDirectory())) buildUi();
        updateFooter();
        if (!new File(game, "wildermyth.jar").isFile()) showInstall();
        else if (!WmCloud.isLoggedIn()) showLogin();
        else if (prefs.getBoolean(PREF_AUTO_LAUNCH, false)) syncAndPlay();
        else show("Ready to play.", "Play", this::syncAndPlay, "Close", this::finish); // a stop for Settings
    }

    private void showInstall() {
        showInstallChoices();
        heading("Welcome");
    }

    private void showInstallChoices() {
        show("Wildermyth's game files are needed.",
                "Use my game files", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_GAME_DIR),
                "Download with Steam", () -> { if (WmCloud.isLoggedIn()) download(); else showLogin(this::download); });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GAME_DIR || resultCode != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        DocumentFile src = DocumentFile.fromTreeUri(this, tree);
        if (src == null || src.findFile("wildermyth.jar") == null || src.findFile("assets") == null) {
            show("That folder isn't a Wildermyth install.", "Try again", this::showInstall);
            return;
        }
        show("Copying…");
        transfer(true);
        worker.execute(() -> {
            try {
                File tmp = new File(game.getPath() + ".partial");
                deleteTree(tmp);
                long[] copied = {0, 0}; // bytes, last UI update
                copyTree(tree, DocumentsContract.getTreeDocumentId(tree), tmp, copied);
                deleteTree(game);
                if (!tmp.renameTo(game)) throw new IllegalStateException("could not move files into place");
                runOnUiThread(() -> { transfer(false); next(); });
            } catch (Exception e) { android.util.Log.e("Wildermyth", "copy failed", e);
                runOnUiThread(() -> { transfer(false); show("Copying failed: " + describe(e), "Try again", this::showInstall); });
            }
        });
    }

    /**
     * Copies a picked folder. One provider query per folder: DocumentFile asks again for every name and
     * type, which for ~42k files cost more than the copying.
     */
    private void copyTree(Uri tree, String dirId, File out, long[] copied) throws Exception {
        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) throw new IllegalStateException("cannot list " + out.getName());
            while (c.moveToNext()) {
                String id = c.getString(0);
                File dest = new File(out, c.getString(1));
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) { copyTree(tree, id, dest, copied); continue; }
                try (InputStream in = getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                     OutputStream o = new FileOutputStream(dest)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) { o.write(buf, 0, n); copied[0] += n; }
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (now - copied[1] < 250) continue;
                copied[1] = now;
                long mb = copied[0] >> 20;
                // ~2.7 GB for a full install; good enough for a bar, the MB count is exact.
                runOnUiThread(() -> { status.setText("Copying… " + mb + " MB"); progress(Math.min(99f, mb / 27f)); });
            }
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void preview(String screen) {
        if ("firstrun".equals(screen)) {
            game = new File(getCacheDir(), "no-game-installed"); // no art: the first-run look
            buildUi();
            showInstall();
        } else if ("synced".equals(screen)) {
            show("All synced.", "Play", () -> {}, "Close", this::finish);
        } else if ("conflict".equals(screen)) {
            conflict(java.util.Arrays.asList("a", "b", "c"), true);
        } else {
            show("All synced.", "Play", () -> {}, "Close", this::finish);
            progress(62);
        }
    }

    private void testDownload() {
        testing = true;
        WmCloud.debugLog(line -> android.util.Log.d("WildermythSteam", line));
        File dest = new File(getCacheDir(), "wm-download-test");
        show("Test: downloading version.txt from Steam…");
        worker.execute(() -> {
            try {
                deleteTree(dest);
                // --ez wm_test_all true: the whole depot, to profile memory on a real download.
                boolean all = getIntent().getBooleanExtra("wm_test_all", false);
                WmCloud.testDownload(dest, all ? java.util.Collections.emptySet() : java.util.Collections.singleton("version.txt"));
                String v = new String(java.nio.file.Files.readAllBytes(new File(dest, "version.txt").toPath())).trim();
                android.util.Log.i("Wildermyth", "test download OK: version.txt = " + v);
                runOnUiThread(() -> show("Test download OK. version.txt says: " + v, "Close", this::finish));
            } catch (Exception e) {
                android.util.Log.e("Wildermyth", "test download failed", e);
                runOnUiThread(() -> show("Test download failed: " + describe(e), "Close", this::finish));
            }
        });
    }

    private void download() {
        show("Downloading…");
        transfer(true);
        worker.execute(() -> {
            try {
                File tmp = new File(game.getPath() + ".partial");
                long[] lastUi = {0};
                WmCloud.downloadGame(tmp, pct -> {
                    // Called per chunk (~42k); redrawing each time cost more than the download.
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastUi[0] < 250) return;
                    lastUi[0] = now;
                    runOnUiThread(() -> {
                        status.setText(String.format(java.util.Locale.ROOT, "Downloading… %.0f%%", pct));
                        progress(pct);
                    });
                });
                deleteTree(game);
                if (!tmp.renameTo(game)) throw new IllegalStateException("could not move files into place");
                runOnUiThread(() -> { transfer(false); next(); });
            } catch (Exception e) { android.util.Log.e("Wildermyth", "download failed", e);
                runOnUiThread(() -> { transfer(false); show("Download failed: " + describe(e), "Try again", this::download, "Back", this::showInstall); });
            }
        });
    }

    private void showLogin() { showLogin(this::next); }

    /** Signs in once; the same sign-in then serves downloads, saves and achievements. */
    private void showLogin(Runnable then) {
        show("Sign in: in the Steam app, open Steam Guard and scan this code.");
        worker.execute(() -> {
            try {
                WmCloud.login(url -> runOnUiThread(() -> { qr.setImageBitmap(qrBitmap(url)); qr.setVisibility(View.VISIBLE); }));
                runOnUiThread(() -> { qr.setVisibility(View.GONE); then.run(); });
            } catch (Exception e) { android.util.Log.e("Wildermyth", "sync step failed", e);
                runOnUiThread(() -> show("Steam sign-in failed: " + describe(e), "Try again", () -> showLogin(then)));
            }
        });
    }

    private void syncAndPlay() {
        show("Syncing saves…");
        worker.execute(() -> {
            try {
                List<Integer> owned = WmCloud.beforePlay(game);
                if (owned != null) saveOwnedDlc(owned);
                runOnUiThread(this::launchGame);
            } catch (ConflictException e) { android.util.Log.e("Wildermyth", "sync step failed", e);
                runOnUiThread(() -> conflict(e.getFiles(), true));
            } catch (Exception e) { android.util.Log.e("Wildermyth", "sync step failed", e);
                runOnUiThread(() -> show("Can't reach Steam. Saves will sync next time.",
                        "Play offline", this::launchGame, "Retry", this::syncAndPlay));
            }
        });
    }

    /** Both sides changed. [beforePlay] decides what happens after the choice. */
    private void conflict(List<String> files, boolean beforePlay) {
        show("Both this device and Steam Cloud changed. Keep which? The other copy is backed up.",
                "Keep this device", () -> resolve(true, beforePlay),
                "Use Steam Cloud", () -> resolve(false, beforePlay));
        heading("Saves differ");
    }

    private void resolve(boolean keepDevice, boolean beforePlay) {
        show(keepDevice ? "Uploading this device's saves…" : "Downloading saves from Steam Cloud…");
        worker.execute(() -> {
            try {
                if (keepDevice) WmCloud.push(game, true); else WmCloud.pull(game, true);
                runOnUiThread(beforePlay ? this::launchGame : this::afterSession);
            } catch (Exception e) { android.util.Log.e("Wildermyth", "sync step failed", e);
                runOnUiThread(() -> show("Sync failed: " + describe(e), "Retry", () -> resolve(keepDevice, beforePlay)));
            }
        });
    }

    /** Steam's answer on owned DLC, kept for the game's launch and for offline play. */
    private void saveOwnedDlc(List<Integer> owned) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : owned) sb.append(sb.length() == 0 ? "" : ",").append(id);
        prefs.edit().putString(WildermythLauncher.PREF_OWNED_DLC, sb.toString()).commit();
    }

    private void launchGame() {
        prefs.edit().putBoolean(PREF_SESSION, true).commit();
        show("Starting Wildermyth…");
        startActivity(new Intent(this, MainActivity.class).putExtra(WildermythLauncher.EXTRA, true));
    }

    private void afterSession() {
        // Game files gone (reinstall, moved): there is nothing to upload, and uploading "nothing" would
        // read as deleting every save.
        if (!new File(game, "players").isDirectory()) {
            prefs.edit().putBoolean(PREF_SESSION, false).commit();
            next();
            return;
        }
        show("Uploading saves…");
        worker.execute(() -> {
            try {
                WmCloud.afterPlay(game); // achievements failing there waits for the next session
            } catch (CloudChangedException e) { android.util.Log.e("Wildermyth", "sync step failed", e);
                runOnUiThread(() -> conflict(java.util.Collections.singletonList("(changed on another device)"), false));
                return;
            } catch (Exception e) { android.util.Log.e("Wildermyth", "sync step failed", e);
                runOnUiThread(() -> show("Upload failed. Saves stay here and sync next time.", "Retry", this::afterSession, "Play", this::syncAndPlay));
                return;
            }
            prefs.edit().putBoolean(PREF_SESSION, false).commit();
            runOnUiThread(() -> show("All synced.", "Play", this::syncAndPlay, "Close", this::finish));
        });
    }

    /**
     * On disconnect JavaSteam cancels its pending jobs and the CancellationException escapes on its
     * network thread. The desktop JVM just prints that; Android kills the app. The waiting caller
     * already sees the failure, so only that exception from JavaSteam is swallowed.
     */
    private static void ignoreJavaSteamCancellations() {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof JavaSteamGuard) return;
        Thread.setDefaultUncaughtExceptionHandler(new JavaSteamGuard(prev));
    }

    private static final class JavaSteamGuard implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler prev;
        JavaSteamGuard(Thread.UncaughtExceptionHandler prev) { this.prev = prev; }

        // Created inside CompletableFuture.cancel, so JavaSteam frames sit below the top one.
        private static boolean fromJavaSteam(Throwable e) {
            for (StackTraceElement f : e.getStackTrace()) if (f.getClassName().startsWith("in.dragonbra.javasteam.")) return true;
            return false;
        }

        @Override public void uncaughtException(Thread t, Throwable e) {
            if (e instanceof java.util.concurrent.CancellationException && fromJavaSteam(e)) {
                android.util.Log.w("Wildermyth", "ignored JavaSteam job cancellation on " + t.getName(), e);
                return;
            }
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    /** A message a person can act on; some exceptions (timeouts, NPEs) carry none. */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        return root.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private boolean gameRunning() {
        List<ActivityManager.RunningAppProcessInfo> procs = ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getRunningAppProcesses();
        if (procs != null) for (ActivityManager.RunningAppProcessInfo p : procs) if (p.processName.endsWith(":game")) return true;
        return false;
    }

    private void show(String message) {
        heading.setVisibility(View.GONE);
        status.setText(message);
        buttons.removeAllViews();
        progress(-1);
        screenHeading = null;
        orientButtons();
        refreshMore(); // no buttons: busy, no Settings link
    }

    /** A title above the current message; call after show(). */
    private void heading(String h) {
        screenHeading = h;
        heading.setText(h);
        heading.setVisibility(View.VISIBLE);
    }

    private void show(String message, String label, Runnable action) {
        show(message);
        addButton(label, action);
        focusFirst(); // the first button takes controller focus
        screen = () -> show(message, label, action);
        refreshMore();
    }

    private void show(String message, String label, Runnable action, String label2, Runnable action2) {
        show(message, label, action);
        addButton(label2, action2);
        screen = () -> show(message, label, action, label2, action2);
    }

    private void addButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        theme.style(b);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(theme.dp(300), theme.dp(56));
        lp.leftMargin = lp.rightMargin = theme.dp(8);
        buttons.addView(b, lp);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(WildermythTheme.TEXT);
        if (theme != null) t.setTypeface(theme.font);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, 16, 0, 16);
        return t;
    }

    private static Bitmap qrBitmap(String url) {
        boolean[][] m = WmCloud.qrMatrix(url);
        int q = 2, n = m.length + 2 * q; // with a quiet zone
        int[] px = new int[n * n];
        java.util.Arrays.fill(px, Color.WHITE);
        for (int y = 0; y < m.length; y++)
            for (int x = 0; x < m[y].length; x++) if (m[y][x]) px[(y + q) * n + x + q] = Color.BLACK;
        Bitmap small = Bitmap.createBitmap(px, n, n, Bitmap.Config.RGB_565);
        return Bitmap.createScaledBitmap(small, n * 12, n * 12, false); // no filtering: crisp modules
    }
}
