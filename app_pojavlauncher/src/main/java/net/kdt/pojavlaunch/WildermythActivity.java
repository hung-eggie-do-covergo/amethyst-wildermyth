package net.kdt.pojavlaunch;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private TextView status;
    private ImageView qr;
    private LinearLayout buttons;
    private File game;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("wildermyth", MODE_PRIVATE);
        game = WildermythLauncher.gameDir(this);
        WmCloud.configure(new File(getFilesDir(), "wmcloud"), line -> runOnUiThread(() -> status.setText(line)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(28, 22, 18));
        root.setPadding(48, 48, 48, 48);
        TextView title = text("Wildermyth", 34);
        status = text("", 18);
        qr = new ImageView(this);
        qr.setVisibility(View.GONE);
        buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        root.addView(title);
        root.addView(status);
        root.addView(qr, new LinearLayout.LayoutParams(560, 560));
        root.addView(buttons);
        setContentView(root);
        if (!prefs.getBoolean(PREF_SESSION, false)) next();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the game (or relaunched after it crashed): upload before anything else.
        if (prefs.getBoolean(PREF_SESSION, false) && !gameRunning()) afterSession();
    }

    /** Walks the setup steps in order; each one calls back here when done. */
    private void next() {
        if (!new File(game, "wildermyth.jar").isFile()) showInstall();
        else if (!WmCloud.isLoggedIn()) showLogin();
        else syncAndPlay();
    }

    private void showInstall() {
        show("Wildermyth's game files are needed. Point to a folder with your own copy of the game "
                + "(wildermyth.jar, assets and lib).",
                "Use my game files", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_GAME_DIR),
                "Download with Steam", () -> show("Downloading through Steam is not available yet. "
                        + "Use your own game files for now.", "Back", this::next));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GAME_DIR || resultCode != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        DocumentFile src = DocumentFile.fromTreeUri(this, tree);
        if (src == null || src.findFile("wildermyth.jar") == null || src.findFile("assets") == null) {
            show("That folder does not look like Wildermyth: wildermyth.jar or assets/ is missing.", "Try again", this::showInstall);
            return;
        }
        show("Copying game files…");
        worker.execute(() -> {
            try {
                File tmp = new File(game.getPath() + ".partial");
                deleteTree(tmp);
                long[] copied = {0};
                copyTree(src, tmp, copied);
                deleteTree(game);
                if (!tmp.renameTo(game)) throw new IllegalStateException("could not move files into place");
                runOnUiThread(this::next);
            } catch (Exception e) {
                runOnUiThread(() -> show("Copying failed: " + e.getMessage(), "Try again", this::showInstall));
            }
        });
    }

    private void copyTree(DocumentFile dir, File out, long[] copied) throws Exception {
        if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);
        for (DocumentFile f : dir.listFiles()) {
            File dest = new File(out, f.getName());
            if (f.isDirectory()) { copyTree(f, dest, copied); continue; }
            try (InputStream in = getContentResolver().openInputStream(f.getUri()); OutputStream o = new FileOutputStream(dest)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) { o.write(buf, 0, n); copied[0] += n; }
            }
            long mb = copied[0] >> 20;
            runOnUiThread(() -> status.setText("Copying game files… " + mb + " MB"));
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void showLogin() {
        show("Sign in to Steam to sync your saves and achievements. In the Steam mobile app, open "
                + "Steam Guard and scan this code.");
        worker.execute(() -> {
            try {
                WmCloud.login(url -> runOnUiThread(() -> { qr.setImageBitmap(qrBitmap(url)); qr.setVisibility(View.VISIBLE); }));
                runOnUiThread(() -> { qr.setVisibility(View.GONE); next(); });
            } catch (Exception e) {
                runOnUiThread(() -> show("Steam sign-in failed: " + e.getMessage(), "Try again", this::showLogin));
            }
        });
    }

    private void syncAndPlay() {
        show("Syncing saves from Steam Cloud…");
        worker.execute(() -> {
            try {
                WmCloud.pull(game, false);
                runOnUiThread(this::launchGame);
            } catch (ConflictException e) {
                runOnUiThread(() -> conflict(e.getFiles(), true));
            } catch (Exception e) {
                runOnUiThread(() -> show("Could not reach Steam Cloud: " + e.getMessage()
                                + "\nIf you play now, your saves upload next time you are online.",
                        "Play anyway", this::launchGame, "Retry", this::syncAndPlay));
            }
        });
    }

    /** Both sides changed. [beforePlay] decides what happens after the choice. */
    private void conflict(List<String> files, boolean beforePlay) {
        new AlertDialog.Builder(this)
                .setTitle("Saves differ from Steam Cloud")
                .setMessage(files.size() + " save file(s) on this device differ from the ones in Steam Cloud, "
                        + "and both have changed. Which saves do you want to keep? The other copy is backed up on "
                        + "this device either way.")
                .setCancelable(false)
                .setPositiveButton("Keep this device", (d, w) -> resolve(true, beforePlay))
                .setNegativeButton("Use Steam Cloud", (d, w) -> resolve(false, beforePlay))
                .show();
    }

    private void resolve(boolean keepDevice, boolean beforePlay) {
        show(keepDevice ? "Uploading this device's saves…" : "Downloading saves from Steam Cloud…");
        worker.execute(() -> {
            try {
                if (keepDevice) WmCloud.push(game, true); else WmCloud.pull(game, true);
                runOnUiThread(beforePlay ? this::launchGame : this::afterSession);
            } catch (Exception e) {
                runOnUiThread(() -> show("Sync failed: " + e.getMessage(), "Retry", () -> resolve(keepDevice, beforePlay)));
            }
        });
    }

    private void launchGame() {
        prefs.edit().putBoolean(PREF_SESSION, true).commit();
        show("Starting Wildermyth…");
        startActivity(new Intent(this, MainActivity.class).putExtra(WildermythLauncher.EXTRA, true));
    }

    private void afterSession() {
        show("Uploading saves to Steam Cloud…");
        worker.execute(() -> {
            try {
                WmCloud.push(game, false);
            } catch (CloudChangedException e) {
                runOnUiThread(() -> conflict(java.util.Collections.singletonList("(changed on another device)"), false));
                return;
            } catch (Exception e) {
                runOnUiThread(() -> show("Could not upload saves: " + e.getMessage()
                        + "\nThey stay on this device and upload next time.", "Retry", this::afterSession, "Play", this::syncAndPlay));
                return;
            }
            try {
                WmCloud.syncAchievements(game);
            } catch (Exception ignored) {
                // Retried after the next session; saves matter more than achievements.
            }
            prefs.edit().putBoolean(PREF_SESSION, false).commit();
            runOnUiThread(() -> show("Saves and achievements are synced with Steam.", "Play", this::syncAndPlay, "Close", this::finish));
        });
    }

    private boolean gameRunning() {
        List<ActivityManager.RunningAppProcessInfo> procs = ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getRunningAppProcesses();
        if (procs != null) for (ActivityManager.RunningAppProcessInfo p : procs) if (p.processName.endsWith(":game")) return true;
        return false;
    }

    private void show(String message) {
        status.setText(message);
        buttons.removeAllViews();
    }

    private void show(String message, String label, Runnable action) {
        show(message);
        addButton(label, action);
        buttons.getChildAt(0).requestFocus(); // the first button takes controller focus
    }

    private void show(String message, String label, Runnable action, String label2, Runnable action2) {
        show(message, label, action);
        addButton(label2, action2);
    }

    private void addButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        buttons.addView(b);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(240, 225, 200));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 16, 0, 16);
        return t;
    }

    private static Bitmap qrBitmap(String url) {
        boolean[][] m = WmCloud.qrMatrix(url);
        int q = 2, scale = 12, size = (m.length + 2 * q) * scale;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        bmp.eraseColor(Color.WHITE);
        for (int y = 0; y < m.length; y++)
            for (int x = 0; x < m[y].length; x++)
                if (m[y][x])
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++) bmp.setPixel((x + q) * scale + dx, (y + q) * scale + dy, Color.BLACK);
        return bmp;
    }
}
