package com.example.penguinz0checker;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    private static final int PICK_CSV = 10, SAVE_CSV = 11;
    private TextView info;
    private ProgressBar bar;
    private Button start;
    private ArrayList<String[]> rows = new ArrayList<>();
    private ArrayList<String[]> results = new ArrayList<>();
    private volatile boolean running = false;
    private Thread worker;
    private Uri outputUri;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 36, 32, 24);
        TextView title = new TextView(this);
        title.setText("YouTube Availability Checker");
        title.setTextSize(23);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Import the inventory CSV. The app checks one video at a time, with a 2-second pause between requests. Keep the app open and connected to the internet.");
        desc.setTextSize(15);
        desc.setPadding(0, 18, 0, 18);
        root.addView(desc);

        Button choose = new Button(this);
        choose.setText("1. Choose inventory CSV");
        choose.setOnClickListener(v -> chooseInput());
        root.addView(choose);

        start = new Button(this);
        start.setText("2. Start checking");
        start.setEnabled(false);
        start.setOnClickListener(v -> startChecks());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Pause checking");
        stop.setOnClickListener(v -> { running = false; update("Pausing after the current request…"); });
        root.addView(stop);

        Button save = new Button(this);
        save.setText("3. Export results CSV");
        save.setOnClickListener(v -> saveOutput());
        root.addView(save);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar);

        info = new TextView(this);
        info.setText("No inventory loaded.");
        info.setTextSize(15);
        info.setPadding(0, 20, 0, 0);
        root.addView(info);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void chooseInput() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("text/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_CSV);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        try {
            if (req == PICK_CSV) {
                Uri u = data.getData();
                rows.clear(); results.clear();
                BufferedReader br = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(u), StandardCharsets.UTF_8));
                String line = br.readLine();
                if (line == null) throw new IOException("Empty CSV");
                List<String> headers = parseCsv(line);
                int idCol = headers.indexOf("youtube_video_id");
                int titleCol = headers.indexOf("title");
                if (idCol < 0) throw new IOException("CSV needs a youtube_video_id column.");
                while ((line = br.readLine()) != null) {
                    List<String> cells = parseCsv(line);
                    if (cells.size() <= idCol) continue;
                    String id = cells.get(idCol).trim();
                    if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
                    String title = titleCol >= 0 && cells.size() > titleCol ? cells.get(titleCol) : "";
                    rows.add(new String[]{title, id});
                }
                br.close();
                start.setEnabled(!rows.isEmpty());
                update("Loaded " + rows.size() + " video IDs. Ready to check.");
            } else if (req == SAVE_CSV) {
                outputUri = data.getData();
                writeOutput();
            }
        } catch (Exception e) { update("Error: " + e.getMessage()); }
    }

    private void startChecks() {
        if (rows.isEmpty() || running) return;
        running = true;
        worker = new Thread(() -> {
            int startAt = results.size();
            for (int i = startAt; i < rows.size() && running; i++) {
                String[] row = rows.get(i);
                String status = "inconclusive_network_or_rate_limit";
                String liveTitle = "", channel = "", code = "";
                try {
                    String watch = "https://www.youtube.com/watch?v=" + row[1];
                    String url = "https://www.youtube.com/oembed?url=" + URLEncoder.encode(watch, "UTF-8") + "&format=json";
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(15000); c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    int rc = c.getResponseCode(); code = String.valueOf(rc);
                    if (rc == 200) {
                        String body = readAll(c.getInputStream());
                        liveTitle = jsonValue(body, "title");
                        channel = jsonValue(body, "author_name");
                        status = "public_oembed_available";
                    } else if (rc == 401 || rc == 403) status = "private_or_embedding_restricted";
                    else if (rc == 404) status = "unavailable_deleted_or_removed";
                    else status = "inconclusive_http_" + rc;
                    c.disconnect();
                } catch (Exception ex) { status = "inconclusive_network_or_rate_limit"; }
                results.add(new String[]{row[0], row[1], "https://www.youtube.com/watch?v=" + row[1], status, liveTitle, channel, code});
                final int done = i + 1;
                runOnUiThread(() -> {
                    bar.setProgress((int)(done * 100f / rows.size()));
                    info.setText("Checked " + done + " of " + rows.size() + "\n" + row[0] + "\nStatus: " + statusLabel(results.get(results.size()-1)[3]) + "\nResults saved in memory. Export when finished.");
                });
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
            running = false;
            runOnUiThread(() -> update("Stopped/finished. " + results.size() + " of " + rows.size() + " checked. Export the results CSV."));
        });
        worker.start();
    }

    private void saveOutput() {
        if (results.isEmpty()) { update("No results to export yet."); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("text/csv");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE, "penguinz0_youtube_status.csv");
        startActivityForResult(i, SAVE_CSV);
    }

    private void writeOutput() {
        try {
            OutputStream os = getContentResolver().openOutputStream(outputUri);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
            w.write("title,youtube_video_id,youtube_url,youtube_status,live_youtube_title,youtube_channel,http_status\n");
            for (String[] r : results) {
                for (int j=0;j<r.length;j++) {
                    if (j>0) w.write(",");
                    w.write(csvEscape(r[j]));
                }
                w.write("\n");
            }
            w.flush(); w.close();
            update("Exported " + results.size() + " results successfully.");
        } catch (Exception e) { update("Export failed: " + e.getMessage()); }
    }

    private void update(String s) { runOnUiThread(() -> info.setText(s)); }
    private static String statusLabel(String s) { return s.replace('_', ' '); }
    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n=in.read(buf))!=-1) b.write(buf,0,n);
        return b.toString("UTF-8");
    }
    private static String jsonValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\");
    }
    private static String csvEscape(String s) { return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\""; }
    private static List<String> parseCsv(String line) {
        ArrayList<String> out = new ArrayList<>(); StringBuilder b = new StringBuilder(); boolean q=false;
        for (int i=0;i<line.length();i++) {
            char c=line.charAt(i);
            if (c=='"') { if (q && i+1<line.length() && line.charAt(i+1)=='"') { b.append('"'); i++; } else q=!q; }
            else if (c==',' && !q) { out.add(b.toString()); b.setLength(0); }
            else b.append(c);
        }
        out.add(b.toString()); return out;
    }
}
