# MapMonkeysDB Scraper (Android)

Android Studio project for collecting publicly available metadata from a YouTube channel without a YouTube Data API key.

## What it exports
- Video title
- Full description
- Video ID and URL
- Upload/release date
- Duration
- View and like counts when available
- Uploader/channel information
- Tags and categories
- Availability and extraction status

It does not download video or audio.

## Build an APK with GitHub Actions
1. Create a new GitHub repository.
2. Upload the contents of this project to the repository root.
3. Open the **Actions** tab.
4. Run **Build MapMonkeysDB Scraper APK** (or push to `main`).
5. Download the `MapMonkeysDB-Scraper-debug` artifact from the completed workflow.
6. Extract the artifact ZIP and install `app-debug.apk` on your Android phone.

## Build locally
Use Android Studio with JDK 17 and Android SDK Platform 35. Open this folder as a project and build the `app` debug variant.

## Use
1. Open the app.
2. Leave the default channel URL or paste another public YouTube channel URL.
3. Tap **Start / Resume scan**.
4. Wait for the scan to finish. Large channels can take many minutes.
5. Tap **Export CSV** and choose where to save it.

## Resume behavior
The app stores its CSV in its private app data directory. If the scan is interrupted, starting it again skips IDs already written to the CSV and continues with the remaining entries.

## Limitations
- Only videos returned by the channel's accessible public uploads listing can be enumerated.
- YouTube may throttle or change its page/extraction behavior. Some metadata rows may be marked as failed.
- Private, deleted, age-restricted, region-restricted, or otherwise inaccessible videos may not yield full metadata.
- Like counts, tags, categories, and descriptions may be unavailable for some videos.
- This is a metadata scraper, not a video downloader.
- Respect YouTube's terms and applicable laws. Do not use it to bypass access controls.

## Technical notes
- Kotlin Android UI.
- Chaquopy embeds Python.
- yt-dlp performs public metadata extraction.
- No YouTube API key or sign-in is configured.
