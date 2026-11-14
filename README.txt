PENGUINZ0 PLAYER — ANDROID STUDIO PROJECT

This is an Android WebView app project (source), not a prebuilt APK.

Build:
1. Install Android Studio on a computer.
2. Open this folder as an existing project.
3. Let Android Studio sync Gradle and install the requested Android SDK.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).
5. Install the generated APK on your Android phone.

Current app capabilities:
- Standard YouTube embedded playback (same /embed/ style as the supplied HTML).
- No Google/YouTube sign-in and no YouTube Data API key.
- Year filter, newest/oldest sorting, random selection, watched/unwatched tracking.
- Imports a JSON catalogue from device storage. The catalogue format is an array:
  [{"id":"11-character-video-id","title":"Video title","publishedAt":"2020-01-02T12:00:00Z"}]
  Optional "thumb" URL is supported.

Important catalogue limitation:
This build deliberately does not call YouTube Data API. It does not yet auto-discover every upload from the channel. A complete, maintained catalogue needs a reliable public metadata source or an exported list. The player itself remains the simple embedded player. YouTube can still choose to show ads in embeds; ad-free playback is not guaranteed.

Package: com.example.penguinz0player
