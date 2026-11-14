package com.example.penguinz0player;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public final class SemanticDataSync {
    private static final String TAG = "P0DataSync";
    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/dannybully666-glitch/joystick-app/gh-pages/semantic/manifest.json";
    private static final String BASE_URL = "https://raw.githubusercontent.com/dannybully666-glitch/joystick-app/gh-pages/semantic/";
    public interface ProgressListener { void onProgress(String stage, String detail); }
    private SemanticDataSync() {}

    public static boolean sync(Context context) { return sync(context, null); }
    public static boolean sync(Context context, ProgressListener listener) {
        try {
            progress(listener, "manifest", "Connecting to GitHub and reading the published manifest…");
            JSONObject remote = new JSONObject(new String(downloadBytes(MANIFEST_URL + "?t=" + System.currentTimeMillis()), "UTF-8"));
            File dir = new File(context.getFilesDir(), "semantic-data");
            if (!dir.exists() && !dir.mkdirs()) return false;
            JSONObject local = readJson(new File(dir, "manifest.json"));
            String remoteCatVersion = remote.optString("catalogue_version", "");
            String remoteIndexVersion = remote.optString("semantic_version", "");
            boolean catChanged = !remoteCatVersion.equals(local.optString("catalogue_version", "")) || !new File(dir, "catalogue.json").exists();
            boolean indexChanged = !remoteIndexVersion.equals(local.optString("semantic_version", "")) || !new File(dir, "semantic-index.json").exists();
            progress(listener, "compare", "Comparing GitHub versions with this device…");

            byte[] catBytes = null, indexBytes = null;
            if (catChanged) {
                progress(listener, "catalogue", "A newer catalogue is available. Downloading it…");
                catBytes = downloadBytes(remote.optString("catalogue_url", BASE_URL + "catalogue.json"));
                progress(listener, "verify", "Checking the catalogue SHA-256 checksum and JSON…");
                if (!sha256(catBytes).equalsIgnoreCase(remote.optString("catalogue_sha256", ""))) return false;
                JSONArray parsed = new JSONArray(new String(catBytes, "UTF-8"));
                if (parsed.length() == 0) return false;
            }
            if (indexChanged) {
                progress(listener, "index", "A newer semantic index is available. Downloading it…");
                indexBytes = downloadBytes(remote.optString("semantic_index_url", BASE_URL + "semantic-index.json"));
                progress(listener, "verify", "Checking the semantic-index SHA-256 checksum and structure…");
                if (!sha256(indexBytes).equalsIgnoreCase(remote.optString("semantic_index_sha256", ""))) return false;
                JSONObject parsed = new JSONObject(new String(indexBytes, "UTF-8"));
                if (parsed.optJSONArray("ids") == null || parsed.optJSONArray("vectors") == null) return false;
            }
            progress(listener, "install", "Installing verified data on this device…");
            if (catBytes != null) writeAtomic(new File(dir, "catalogue.json"), catBytes);
            if (indexBytes != null) writeAtomic(new File(dir, "semantic-index.json"), indexBytes);
            writeAtomic(new File(dir, "manifest.json"), remote.toString().getBytes("UTF-8"));
            String result = !catChanged && !indexChanged ? "Already up to date. No download was needed." :
                "Done. Updated " + (catChanged && indexChanged ? "catalogue and semantic index" : catChanged ? "catalogue" : "semantic index") + ".";
            progress(listener, "complete", result);
            Log.i(TAG, "Sync complete. Catalogue changed=" + catChanged + ", index changed=" + indexChanged);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Semantic data sync failed; keeping previous local data", e);
            progress(listener, "error", "Update failed: " + (e.getMessage() == null ? "network or validation error" : e.getMessage()));
            return false;
        }
    }
    private static void progress(ProgressListener listener, String stage, String detail) {
        if (listener != null) listener.onProgress(stage, detail);
    }
    private static JSONObject readJson(File f) {
        if (!f.exists()) return new JSONObject();
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) { return new JSONObject(); }
    }
    private static byte[] downloadBytes(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        try {
            conn.setInstanceFollowRedirects(true); conn.setConnectTimeout(20000); conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "Penguinz0Player/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " from " + address);
            try (InputStream in = new BufferedInputStream(conn.getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[64 * 1024]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); return out.toByteArray();
            }
        } finally { conn.disconnect(); }
    }
    private static void writeAtomic(File target, byte[] bytes) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); out.getFD().sync(); }
        if (target.exists() && !target.delete()) throw new Exception("Cannot replace " + target);
        if (!tmp.renameTo(target)) throw new Exception("Cannot rename " + tmp);
    }
    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder s = new StringBuilder();
        for (byte b : digest) s.append(String.format("%02x", b & 0xff)); return s.toString();
    }
}
