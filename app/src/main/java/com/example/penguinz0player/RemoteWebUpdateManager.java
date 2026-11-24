package com.example.penguinz0player;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads verified WebView app updates without replacing the installed APK. */
public final class RemoteWebUpdateManager {
    private static final String TAG = "P0RemoteUpdate";
    private static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/dannybully666-glitch/joystick-app/gh-pages/remote-app/manifest.json";
    private static final long MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_UNPACKED_BYTES = 150L * 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRIES = 500;
    private static final int DOWNLOAD_ATTEMPTS = 3;

    private RemoteWebUpdateManager() {}

    public interface ProgressListener {
        void onProgress(String stage, long downloadedBytes, long totalBytes, String message);
    }
    private static void report(ProgressListener listener, String stage, long downloaded,
                               long total, String message) {
        if (listener != null) listener.onProgress(stage, downloaded, total, message);
    }

    public static File activeDirectory(Context context) {
        return new File(context.getFilesDir(), "remote-web");
    }

    /** Installs a previously downloaded update before WebView starts using the files. */
    public static synchronized boolean installPending(Context context) {
        File active = activeDirectory(context);
        File pending = new File(context.getFilesDir(), "remote-web-pending");
        File backup = new File(context.getFilesDir(), "remote-web-backup");
        if (!active.exists() && backup.isDirectory() && new File(backup, "index.html").isFile()) {
            backup.renameTo(active);
        }
        if (!new File(pending, "index.html").isFile()) {
            if (active.isDirectory() && backup.exists()) deleteRecursively(backup);
            return false;
        }
        deleteRecursively(backup);
        boolean hadActive = active.exists();
        if (hadActive && !active.renameTo(backup)) return false;
        if (!pending.renameTo(active)) {
            if (hadActive) backup.renameTo(active);
            return false;
        }
        deleteRecursively(backup);
        Log.i(TAG, "Installed pending WebView update " + readVersion(active));
        return true;
    }

    /** Copies the APK-bundled web app into private storage for first launch/offline fallback. */
    public static synchronized boolean ensureBundledFallback(Context context) {
        File active = activeDirectory(context);
        int installedNativeVersion = 1;
        try {
            installedNativeVersion = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {}

        // Refresh the private web copy after an APK update. Without this check,
        // an older remote HTML copy can continue hiding fixes bundled in the APK.
        if (new File(active, "index.html").isFile()
                && readNativeVersion(active) >= installedNativeVersion) return true;

        File staging = new File(context.getFilesDir(), "remote-web-bootstrap");
        deleteRecursively(staging);
        if (!staging.mkdirs()) return false;
        try {
            copyAssetTree(context, "", staging);
            if (!new File(staging, "index.html").isFile()) return false;
            File version = new File(staging, "version.json");
            try (FileOutputStream out = new FileOutputStream(version)) {
                JSONObject bundled = new JSONObject();
                bundled.put("version", "bundled");
                bundled.put("native_version_code", installedNativeVersion);
                out.write(bundled.toString().getBytes("UTF-8"));
                out.getFD().sync();
            }
            if (active.exists()) deleteRecursively(active);
            return staging.renameTo(active);
        } catch (Exception e) {
            Log.e(TAG, "Could not prepare bundled WebView files", e);
            deleteRecursively(staging);
            return false;
        }
    }

    private static void copyAssetTree(Context context, String path, File destination) throws Exception {
        String[] children = context.getAssets().list(path);
        if (children == null || children.length == 0) {
            String name = path.isEmpty() ? "" : path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) return;
            File target = new File(destination, name);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream in = context.getAssets().open(path);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            return;
        }
        for (String child : children) {
            String childPath = path.isEmpty() ? child : path + "/" + child;
            String[] nested = context.getAssets().list(childPath);
            if (nested != null && nested.length > 0) {
                File dir = new File(destination, child);
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create " + dir);
                copyAssetTree(context, childPath, dir);
            } else {
                File target = new File(destination, child);
                try (InputStream in = context.getAssets().open(childPath);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
        }
    }

    /** Returns JSON status: current, pending, staged, or error. */
    public static synchronized JSONObject checkAndStage(Context context) {
        return checkAndStage(context, null);
    }

    public static synchronized JSONObject checkAndStage(Context context, ProgressListener listener) {
        JSONObject result = new JSONObject();
        File zip = null, staging = null;
        try {
            File active = activeDirectory(context);
            File pending = new File(context.getFilesDir(), "remote-web-pending");
            report(listener, "manifest", 0, 0, "Reading published feature-update manifest…");
            JSONObject manifest = new JSONObject(new String(downloadBytes(
                    MANIFEST_URL + "?t=" + System.currentTimeMillis(), 1024 * 1024), "UTF-8"));
            String version = manifest.optString("version", "");
            String url = manifest.optString("bundle_url", "");
            String expectedHash = manifest.optString("bundle_sha256", "");
            if (version.isEmpty() || url.isEmpty() || !expectedHash.matches("(?i)[a-f0-9]{64}"))
                throw new Exception("The published update manifest is incomplete or invalid.");

            int minNative = manifest.optInt("min_native_version_code", 1);
            int nativeVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
            if (minNative > nativeVersion) {
                result.put("status", "native_required"); result.put("version", version);
                result.put("published_version", version);
                result.put("installed_version", readVersion(active));
                result.put("message", "A newer Android APK is required before this published feature package can be installed.");
                return result;
            }

            if (version.equals(readVersion(active))) {
                deleteRecursively(pending);
                result.put("status", "current"); result.put("version", version);
                result.put("published_version", version); result.put("installed_version", readVersion(active));
                result.put("message", "The published GitHub feature package matches the version installed on this device. This checks the published manifest, not whether a newer GitHub Actions run is still building.");
                return result;
            }
            if (version.equals(readVersion(pending))) {
                result.put("status", "pending"); result.put("version", version);
                result.put("published_version", version); result.put("installed_version", readVersion(active));
                result.put("message", "This verified update is already downloaded and will be applied when the app restarts.");
                return result;
            }

            long advertisedSize = manifest.optLong("bundle_size_bytes", -1L);
            if (advertisedSize > MAX_ARCHIVE_BYTES)
                throw new Exception("The published update exceeds the 100 MB download limit.");

            zip = new File(context.getFilesDir(), "remote-web-update.zip");
            report(listener, "downloading", zip.exists() ? zip.length() : 0, advertisedSize,
                    "Downloading feature package…");
            downloadToFile(url, zip, MAX_ARCHIVE_BYTES, advertisedSize, listener);

            report(listener, "verify", zip.length(), zip.length(), "Verifying SHA-256 checksum…");
            if (!sha256(zip).equalsIgnoreCase(expectedHash)) {
                zip.delete();
                throw new Exception("SHA-256 verification failed. The downloaded package was discarded; try again.");
            }

            staging = new File(context.getFilesDir(), "remote-web-staging");
            deleteRecursively(staging);
            if (!staging.mkdirs()) throw new Exception("Could not create update staging directory.");
            report(listener, "install", zip.length(), zip.length(), "Extracting and validating update files…");
            unzipSafely(zip, staging);
            zip.delete(); zip = null;

            if (!new File(staging, "index.html").isFile() || !new File(staging, "catalogue.js").isFile())
                throw new Exception("The update package is missing required app files (index.html or catalogue.js).");

            JSONObject installed = new JSONObject();
            installed.put("version", version);
            installed.put("native_version_code", nativeVersion);
            try (FileOutputStream out = new FileOutputStream(new File(staging, "version.json"))) {
                out.write(installed.toString().getBytes("UTF-8")); out.getFD().sync();
            }

            deleteRecursively(pending);
            if (!staging.renameTo(pending)) throw new Exception("Could not move the verified update into the pending-install location.");
            staging = null;
            result.put("status", "staged"); result.put("version", version);
            result.put("published_version", version); result.put("installed_version", readVersion(active));
            result.put("bundle_size_bytes", advertisedSize);
            result.put("message", "Downloaded and verified successfully. Restart the app to apply the update.");
            report(listener, "complete", advertisedSize, advertisedSize, "Update downloaded and verified. Restart to apply.");
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Remote WebView update check failed", e);
            if (staging != null) deleteRecursively(staging);
            try {
                result.put("status", "error");
                result.put("message", e.getMessage() == null ? "Feature update failed." : e.getMessage());
            } catch (Exception ignored) {}
            report(listener, "error", 0, 0, e.getMessage() == null ? "Feature update failed." : e.getMessage());
            return result;
        } finally {
            if (zip != null && zip.exists() && zip.length() == 0) zip.delete();
        }
    }

    private static int readNativeVersion(File directory) {
        File file = new File(directory, "version.json");
        if (!file.isFile()) return 0;
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return new JSONObject(new String(out.toByteArray(), "UTF-8")).optInt("native_version_code", 0);
        } catch (Exception e) { return 0; }
    }

    private static String readVersion(File directory) {
        File file = new File(directory, "version.json");
        if (!file.isFile()) return "";
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return new JSONObject(new String(out.toByteArray(), "UTF-8")).optString("version", "");
        } catch (Exception e) { return ""; }
    }

    private static byte[] downloadBytes(String address, long limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(30000); c.setReadTimeout(120000);
            c.setRequestProperty("User-Agent", "Penguinz0Player/1.0");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " while checking the update manifest.");
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > limit) throw new Exception("Update manifest is unexpectedly large.");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    private static void downloadToFile(String address, File target, long limit,
                                       long advertisedSize, ProgressListener listener) throws Exception {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        Exception last = null;

        // Throttle progress independently for each download invocation. Keep these
        // local (not static/class fields) so concurrent or subsequent downloads
        // cannot share stale progress state. They intentionally span retry attempts.
        long lastReportedBytes = 0L;
        long lastReportedAt = 0L;

        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            HttpURLConnection c = null;
            try {
                long offset = part.isFile() ? part.length() : 0L;
                if (offset > limit || (advertisedSize > 0 && offset > advertisedSize)) {
                    part.delete(); offset = 0L;
                }
                c = (HttpURLConnection) new URL(address).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(30000); c.setReadTimeout(120000);
                c.setRequestProperty("User-Agent", "Penguinz0Player/1.0");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
                int code = c.getResponseCode();
                boolean append;
                if (offset > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                    String range = c.getHeaderField("Content-Range");
                    if (range == null || !range.startsWith("bytes " + offset + "-"))
                        throw new Exception("Invalid resume response from server.");
                    append = true;
                } else if (code == HttpURLConnection.HTTP_OK) {
                    append = false; offset = 0L;
                } else {
                    throw new Exception("HTTP " + code + " while downloading the feature package.");
                }
                long responseLength = (long) c.getContentLength();
                long total = advertisedSize > 0 ? advertisedSize
                        : (responseLength > 0 ? offset + responseLength : -1L);
                if (total > limit) throw new Exception("Feature package exceeds the 100 MB download limit.");
                if (advertisedSize > 0 && responseLength > 0 && offset + responseLength != advertisedSize)
                    throw new Exception("Server package size does not match the published manifest.");

                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     FileOutputStream out = new FileOutputStream(part, append)) {
                    byte[] buf = new byte[32768]; int n;
                    long downloaded = append ? offset : 0L;
                    while ((n = in.read(buf)) != -1) {
                        downloaded += n;
                        if (downloaded > limit || (advertisedSize > 0 && downloaded > advertisedSize))
                            throw new Exception("Downloaded feature package exceeded its size limit.");
                        out.write(buf, 0, n);
                        long now = System.currentTimeMillis();
                        if (downloaded - lastReportedBytes >= 256L * 1024L || now - lastReportedAt >= 400L) {
                            report(listener, "downloading", downloaded, total,
                                    "Downloading feature package: " + formatBytes(downloaded)
                                            + (total > 0 ? " / " + formatBytes(total) : ""));
                            lastReportedBytes = downloaded;
                            lastReportedAt = now;
                        }
                    }
                    out.flush(); out.getFD().sync();
                    report(listener, "downloading", downloaded, total,
                            "Downloaded " + formatBytes(downloaded)
                                    + (total > 0 ? " / " + formatBytes(total) : ""));
                }
                long size = part.length();
                if (advertisedSize > 0 && size != advertisedSize)
                    throw new Exception("Download ended early (" + formatBytes(size) + " / " + formatBytes(advertisedSize) + "). Retrying/resuming…");
                if (target.exists() && !target.delete()) throw new Exception("Could not replace previous temporary archive.");
                if (!part.renameTo(target)) throw new Exception("Could not finalize downloaded update archive.");
                return;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "Feature update download attempt " + attempt + " failed", e);
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.startsWith("HTTP ") || msg.contains("Invalid resume")
                        || msg.contains("exceeded") || msg.contains("does not match"))
                    part.delete();
                if (attempt < DOWNLOAD_ATTEMPTS) {
                    try { Thread.sleep(1200L * attempt); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Update download interrupted.", interrupted);
                    }
                }
            } finally { if (c != null) c.disconnect(); }
        }
        throw new Exception("Download failed after " + DOWNLOAD_ATTEMPTS + " attempts: "
                + (last == null ? "network error" : last.getMessage()), last);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "unknown size";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void unzipSafely(File archive, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        long expanded = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) throw new Exception("Update package contains too many files");
                File outFile = new File(destination, entry.getName());
                String canonical = outFile.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new Exception("Unsafe path in update package");
                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) throw new Exception("Could not create update folder");
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new Exception("Could not create update folder");
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = zip.read(buffer)) != -1) {
                            expanded += n; if (expanded > MAX_UNPACKED_BYTES) throw new Exception("Unpacked update is too large");
                            out.write(buffer, 0, n);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[16384]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
