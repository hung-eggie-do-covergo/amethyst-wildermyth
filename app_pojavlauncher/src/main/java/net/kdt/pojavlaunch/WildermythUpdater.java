package net.kdt.pojavlaunch;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import java.util.function.IntConsumer;

/**
 * Updates from the project's GitHub releases. Android always asks the player to confirm an update for an
 * app that isn't from a store; this finds a newer release, downloads its APK, and hands it to the installer.
 */
final class WildermythUpdater {
    static final String PREF = "wm_auto_update";
    private static final String PREF_LAST_CHECK = "wm_update_checked";
    private static final String LATEST = "https://api.github.com/repos/hung-eggie-do-covergo/wildermyth-android/releases/latest";
    private static final long EVERY_MS = 6 * 3600_000L;
    private static final String ACTION = "dev.eggnet.wildermyth.UPDATE_STATUS";

    /** A release newer than this app. */
    static final class Update {
        final String version, url;
        final long size;

        Update(String version, String url, long size) {
            this.version = version;
            this.url = url;
            this.size = size;
        }
    }

    private WildermythUpdater() {}

    /** Whether to look now: on, and not looked in the last few hours. */
    static boolean due(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("wildermyth", Context.MODE_PRIVATE);
        return pretendOld || p.getBoolean(PREF, true) && System.currentTimeMillis() - p.getLong(PREF_LAST_CHECK, 0) > EVERY_MS;
    }

    /** The latest release if it is newer than this app, else null. Blocking: call off the UI thread. */
    /** Debug: treat any release as newer, to test the flow end to end. */
    static boolean pretendOld;

    static Update check(Context ctx) throws Exception {
        ctx.getSharedPreferences("wildermyth", Context.MODE_PRIVATE).edit()
                .putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
        HttpURLConnection c = open(LATEST, "application/vnd.github+json");
        JSONObject release;
        try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            release = new JSONObject(s.hasNext() ? s.next() : "{}");
        }
        String tag = release.optString("tag_name");
        if (!pretendOld && versionCode(tag) <= installedVersionCode(ctx)) return null;
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a.optString("name").endsWith(".apk"))
                return new Update(tag, a.optString("browser_download_url"), a.optLong("size"));
        }
        return null;
    }

    /** Release tags are v0.N; the app's version code is 1000000N (see app build.gradle). */
    static int versionCode(String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^v0\\.(\\d+)$").matcher(tag == null ? "" : tag);
        return m.find() ? 10_000_000 + Integer.parseInt(m.group(1)) : -1;
    }

    @SuppressWarnings("deprecation")
    private static long installedVersionCode(Context ctx) throws Exception {
        PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    /** Downloads the APK into the cache; progress in percent. Blocking. */
    static File download(Context ctx, Update u, IntConsumer progress) throws Exception {
        File apk = new File(ctx.getCacheDir(), "update.apk");
        HttpURLConnection c = open(u.url, "application/octet-stream");
        long total = u.size > 0 ? u.size : c.getContentLengthLong(), done = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[1 << 16];
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct != last) progress.accept(last = pct);
            }
        }
        if (total > 0 && done != total) throw new java.io.IOException("download incomplete");
        return apk;
    }

    /** Android needs "Install unknown apps" for this app before it can update itself. */
    static boolean mayInstall(Context ctx) {
        return Build.VERSION.SDK_INT < 26 || ctx.getPackageManager().canRequestPackageInstalls();
    }

    /** Opens Android's "Install unknown apps" page for this app. */
    static void askToInstall(Activity activity) {
        activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + activity.getPackageName())));
    }

    /** Why an update didn't install, in the player's words. */
    static String why(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "The update was cancelled.";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "Android blocked the update.";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "There isn't enough storage for the update.";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "This download can't update this copy of the app.";
            default: return "The update didn't install.";
        }
    }

    /** Hands the APK to Android's installer, which asks the player to confirm; {@code failed} hears why not. */
    static void install(Activity activity, File apk, java.util.function.IntConsumer failed) throws Exception {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            activity.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return; // the installer reports again once the player answers
                    }
                    try {
                        activity.unregisterReceiver(this);
                    } catch (IllegalArgumentException ignored) {
                        // already gone
                    }
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        android.util.Log.w("Wildermyth", "update: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                        failed.accept(status);
                    }
                }
            }, new IntentFilter(ACTION), Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
            Intent status = new Intent(ACTION).setPackage(activity.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(activity, id, status, flags).getIntentSender());
        }
    }

    private static HttpURLConnection open(String url, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Accept", accept);
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "wildermyth-android-updater");
        c.setInstanceFollowRedirects(true); // GitHub sends asset downloads to its CDN
        if (c.getResponseCode() / 100 != 2) throw new java.io.IOException("HTTP " + c.getResponseCode() + " for " + url);
        return c;
    }
}
