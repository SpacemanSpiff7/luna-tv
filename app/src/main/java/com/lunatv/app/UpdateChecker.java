package com.lunatv.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks GitHub Releases for a newer build, downloads the APK, and hands it
 * to the system installer. Runs automatically once a day and on demand from
 * the settings dialog.
 */
class UpdateChecker {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/SpacemanSpiff7/luna-tv/releases/latest";
    private static final String PREF_LAST_CHECK = "last_update_check";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 15000;

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean checkInProgress = new AtomicBoolean(false);
    private final AtomicBoolean downloadInProgress = new AtomicBoolean(false);

    UpdateChecker(Activity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
    }

    /** Silent check, at most once per day. */
    void checkDaily() {
        long last = prefs.getLong(PREF_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        check(false);
    }

    /** User-initiated check; always reports the outcome. */
    void checkNow() {
        toast("Checking for updates…", Toast.LENGTH_SHORT);
        check(true);
    }

    private void check(boolean manual) {
        if (!checkInProgress.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                JSONObject release = fetchJson(LATEST_RELEASE_API);
                String tag = release.optString("tag_name", "");
                int latest = parseBuildNumber(tag);
                String apkUrl = findApkUrl(release);

                if (latest > BuildConfig.BUILD_NUMBER && apkUrl != null) {
                    // Timestamp is recorded in promptInstall, once the prompt
                    // is actually shown — not here, so a prompt dropped because
                    // the activity is finishing doesn't burn the daily budget.
                    mainHandler.post(() -> promptInstall(tag, apkUrl));
                } else {
                    recordCheckTime();
                    if (manual) {
                        if (latest > BuildConfig.BUILD_NUMBER) {
                            // Newer release exists but CI hasn't attached the
                            // APK (yet) — don't claim the user is up to date.
                            toastOnMain("Update " + tag
                                    + " found, but no APK is attached yet. Try again later.");
                        } else {
                            toastOnMain("You're up to date ("
                                    + BuildConfig.VERSION_NAME + ").");
                        }
                    }
                }
            } catch (Exception e) {
                // Deliberately not recording the check time: a network hiccup
                // shouldn't silence the daily check for another 24 hours.
                if (manual) {
                    toastOnMain("Update check failed: " + e.getMessage());
                }
            } finally {
                checkInProgress.set(false);
            }
        }).start();
    }

    private void recordCheckTime() {
        prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    private void promptInstall(String tag, String apkUrl) {
        // The daily check can finish while the activity is going away
        // (e.g. user backed out immediately after launch).
        if (activity.isFinishing() || activity.isDestroyed()) return;
        recordCheckTime();
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("Luna TV " + tag + " is available.\n\nInstalled: "
                        + BuildConfig.VERSION_NAME + "\n\nDownload and install now?")
                .setPositiveButton("Update", (d, w) -> startUpdate(apkUrl))
                .setNegativeButton("Later", null)
                .show();
    }

    private void startUpdate(String apkUrl) {
        // Manifest permission alone isn't enough on API 26+: the user must
        // grant "Install unknown apps" to this app once, or the installer
        // rejects the APK after the download with a cryptic error.
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                activity.startActivity(new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
                toast("Allow Luna TV to install apps, then check for updates again.",
                        Toast.LENGTH_LONG);
            } catch (Exception e) {
                // Some TV builds have no handler for this settings screen.
                toast("Allow installs first: Settings > Apps > Special app access"
                        + " > Install unknown apps > Luna TV", Toast.LENGTH_LONG);
            }
            return;
        }
        download(apkUrl);
    }

    private void download(String apkUrl) {
        if (!downloadInProgress.compareAndSet(false, true)) {
            toast("Download already in progress…", Toast.LENGTH_SHORT);
            return;
        }
        toast("Downloading update…", Toast.LENGTH_LONG);
        new Thread(() -> {
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("can't create download directory");
                }
                File apk = new File(dir, "luna-tv-update.apk");
                // Download to a temp name and rename, so a half-written file
                // can never be handed to the installer.
                File part = new File(dir, "luna-tv-update.apk.part");

                HttpURLConnection conn = open(apkUrl);
                conn.setRequestProperty("Accept", "application/octet-stream");
                try (InputStream in = getInputStreamChecked(conn);
                     OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    conn.disconnect();
                }
                if (!part.renameTo(apk) && !(apk.delete() && part.renameTo(apk))) {
                    throw new IOException("can't finalize downloaded file");
                }

                Uri uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", apk);
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                mainHandler.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    try {
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        // Rare TV builds have no package-installer UI at all.
                        toast("No installer available on this device. Sideload the"
                                + " APK from GitHub Releases instead.", Toast.LENGTH_LONG);
                    }
                });
            } catch (Exception e) {
                toastOnMain("Update download failed: " + e.getMessage());
            } finally {
                downloadInProgress.set(false);
            }
        }).start();
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /** Extract N from tags like "v1.0.N"; -1 if the tag doesn't match. */
    private static int parseBuildNumber(String tag) {
        int dot = tag.lastIndexOf('.');
        if (dot < 0 || dot == tag.length() - 1) return -1;
        try {
            return Integer.parseInt(tag.substring(dot + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String findApkUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (name.endsWith(".apk")) {
                String url = asset.optString("browser_download_url", "");
                if (!url.isEmpty()) return url;
            }
        }
        return null;
    }

    private static JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection conn = open(url);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getInputStreamChecked(conn), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Reject non-200 responses. getInputStream() already throws for >= 400,
     * but e.g. a captive portal can answer 200 with an HTML page — the caller
     * of open() sets an Accept header, and anything that isn't a clean 200
     * would otherwise be parsed as JSON or installed as an APK.
     */
    private static InputStream getInputStreamChecked(HttpURLConnection conn)
            throws IOException {
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + code);
        }
        return conn.getInputStream();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "luna-tv/" + BuildConfig.VERSION_NAME);
        return conn;
    }

    private void toast(String msg, int duration) {
        Toast.makeText(activity, msg, duration).show();
    }

    private void toastOnMain(String msg) {
        mainHandler.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            toast(msg, Toast.LENGTH_LONG);
        });
    }
}
