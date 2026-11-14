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
    private static final long MAX_ARCHIVE_BYTES = 20L * 1024L * 1024L;
    private static final long MAX_UNPACKED_BYTES = 35L * 1024L * 1024L;

    private RemoteWebUpdateManager() {}

    public static File activeDirectory(Context context) {
        return new File(context.getFilesDir(), "remote-web");
    }

    /** Installs a previously downloaded update before WebView starts using the files. */
    public static synchronized boolean installPending(Context context) {
        File active = activeDirectory(context);
        File pending = new File(context.getFilesDir(), "remote-web-pending");
        File backup = new File(context.getFilesDir(), "remote-web-backup");
        if (!new File(pending, "index.html").isFile()) return false;
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
        JSONObject result = new JSONObject();
        try {
            File active = activeDirectory(context);
            File pending = new File(context.getFilesDir(), "remote-web-pending");
            JSONObject manifest = new JSONObject(new String(downloadBytes(MANIFEST_URL + "?t=" + System.currentTimeMillis(), 1024 * 1024), "UTF-8"));
            String version = manifest.optString("version", "");
            String url = manifest.optString("bundle_url", "");
            String expectedHash = manifest.optString("bundle_sha256", "");
            if (version.isEmpty() || url.isEmpty() || expectedHash.length() != 64) throw new Exception("Remote update manifest is incomplete");
            int minimumNativeVersion = manifest.optInt("min_native_version_code", 1);
            int installedNativeVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
            if (minimumNativeVersion > installedNativeVersion) {
                result.put("status", "native_required");
                result.put("version", version);
                result.put("message", "This update requires a newer Android APK. Use the Android build workflow to install the native update first.");
                return result;
            }

            String activeVersion = readVersion(active);
            String pendingVersion = readVersion(pending);
            if (version.equals(activeVersion)) {
                deleteRecursively(pending);
                result.put("status", "current");
                result.put("version", version);
                result.put("message", "The app's web features are already up to date.");
                return result;
            }
            if (version.equals(pendingVersion)) {
                result.put("status", "pending");
                result.put("version", version);
                result.put("message", "An update is downloaded and will be applied the next time the app starts.");
                return result;
            }

            File zip = new File(context.getFilesDir(), "remote-web-update.zip");
            downloadToFile(url, zip, MAX_ARCHIVE_BYTES);
            if (!sha256(zip).equalsIgnoreCase(expectedHash)) throw new Exception("Update archive checksum did not match");

            File staging = new File(context.getFilesDir(), "remote-web-staging");
            deleteRecursively(staging);
            if (!staging.mkdirs()) throw new Exception("Could not create update staging directory");
            unzipSafely(zip, staging);
            zip.delete();
            if (!new File(staging, "index.html").isFile() || !new File(staging, "catalogue.js").isFile()) {
                deleteRecursively(staging);
                throw new Exception("Update package is missing required app files");
            }
            JSONObject installed = new JSONObject();
            installed.put("version", version);
            installed.put("native_version_code", installedNativeVersion);
            try (FileOutputStream out = new FileOutputStream(new File(staging, "version.json"))) {
                out.write(installed.toString().getBytes("UTF-8"));
                out.getFD().sync();
            }
            deleteRecursively(pending);
            if (!staging.renameTo(pending)) {
                deleteRecursively(staging);
                throw new Exception("Could not prepare the downloaded update");
            }
            result.put("status", "staged");
            result.put("version", version);
            result.put("message", "A new app update has been downloaded and verified. Restart the app to apply it.");
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Remote WebView update check failed", e);
            try {
                result.put("status", "error");
                result.put("message", e.getMessage() == null ? "Update check failed" : e.getMessage());
            } catch (Exception ignored) {}
            return result;
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
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000); connection.setReadTimeout(120000);
            connection.setRequestProperty("User-Agent", "Penguinz0Player/1.0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " while checking updates");
            try (InputStream in = new BufferedInputStream(connection.getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > limit) throw new Exception("Downloaded update data is too large");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    private static void downloadToFile(String address, File target, long limit) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000); connection.setReadTimeout(120000);
            connection.setRequestProperty("User-Agent", "Penguinz0Player/1.0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " while downloading app update");
            long total = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream()); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384]; int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n; if (total > limit) throw new Exception("App update archive is too large");
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            if (target.exists()) target.delete();
            if (!part.renameTo(target)) throw new Exception("Could not save app update archive");
        } catch (Exception e) { part.delete(); throw e; }
        finally { connection.disconnect(); }
    }

    private static void unzipSafely(File archive, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        long expanded = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 200) throw new Exception("Update package contains too many files");
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
