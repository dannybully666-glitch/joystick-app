package com.example.mapmonkeysscraper

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: Button
    private lateinit var exportButton: Button
    private var csvFile: File? = null
    private var scanning = false

    private val progressPoll = object : Runnable {
        override fun run() {
            if (!scanning) return
            val text = File(filesDir, "scrape_progress.txt").let {
                if (it.exists()) it.readText() else "Starting scraper…"
            }
            status.text = text
            handler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        buildUi()
        val existing = File(filesDir, "mapmonkeysdb_video_inventory.csv")
        if (existing.exists() && existing.length() > 0) {
            csvFile = existing
            exportButton.isEnabled = true
            status.text = "A previous CSV is saved. You can export it or resume the scan."
        }
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            gravity = Gravity.TOP
        }

        val heading = TextView(this).apply {
            text = "MapMonkeysDB Scraper"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        outer.addView(heading)

        val intro = TextView(this).apply {
            text = "Collect public YouTube video metadata without an API key. The app saves titles, full descriptions, IDs, dates, durations, view counts and tags."
            textSize = 15f
            setPadding(0, 12, 0, 18)
        }
        outer.addView(intro)

        urlInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            hint = "YouTube channel URL"
            setText("https://www.youtube.com/@mapmonkeysdb/videos")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        outer.addView(urlInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        scanButton = Button(this).apply {
            text = "Start / Resume scan"
            setOnClickListener { startScan() }
        }
        outer.addView(scanButton)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        outer.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 12
        ))

        status = TextView(this).apply {
            text = "Ready. A large channel may take a while to scan."
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        outer.addView(status)

        exportButton = Button(this).apply {
            text = "Export CSV"
            isEnabled = false
            setOnClickListener { chooseExportLocation() }
        }
        outer.addView(exportButton)

        val note = TextView(this).apply {
            text = "Notes: This retrieves metadata only, not video files. It may take several minutes. Keep the app open and connected to the internet. If interrupted, run it again to resume from the saved CSV. YouTube may temporarily limit automated requests."
            textSize = 12f
            setPadding(0, 18, 0, 0)
        }
        outer.addView(note)

        val scroll = ScrollView(this).apply { addView(outer) }
        setContentView(scroll)
    }

    private fun startScan() {
        if (scanning) return
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            status.text = "Enter a YouTube channel URL first."
            return
        }

        scanning = true
        scanButton.isEnabled = false
        exportButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Starting…"
        handler.post(progressPoll)

        executor.execute {
            try {
                val module = Python.getInstance().getModule("scraper")
                val result = module.callAttr("scrape_channel", url, filesDir.absolutePath)
                val path = result.toString()
                runOnUiThread {
                    csvFile = File(path)
                    status.text = "Scan finished. CSV is ready to export."
                    exportButton.isEnabled = csvFile?.exists() == true
                    scanButton.isEnabled = true
                    progress.visibility = View.GONE
                    scanning = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Scan stopped: ${e.message ?: e.javaClass.simpleName}\nYou can try again; completed rows are retained."
                    scanButton.isEnabled = true
                    exportButton.isEnabled = File(filesDir, "mapmonkeysdb_video_inventory.csv").exists()
                    progress.visibility = View.GONE
                    scanning = false
                }
            }
        }
    }

    private fun chooseExportLocation() {
        val source = csvFile ?: File(filesDir, "mapmonkeysdb_video_inventory.csv")
        if (!source.exists()) {
            status.text = "No CSV file is available yet."
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "mapmonkeysdb_video_inventory.csv")
        }
        startActivityForResult(intent, 501)
    }

    @Deprecated("Deprecated by Android API; retained for broad device compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 501 && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                val source = csvFile ?: File(filesDir, "mapmonkeysdb_video_inventory.csv")
                contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                }
                status.text = "CSV exported successfully."
            } catch (e: Exception) {
                status.text = "Export failed: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(progressPoll)
        executor.shutdown()
    }
}
