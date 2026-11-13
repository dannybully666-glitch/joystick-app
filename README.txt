Penguinz0 YouTube Availability Checker — Android Studio project

Important:
- This is an Android app source project, not a precompiled APK.
- This environment does not have the Android SDK/Gradle build tools needed to produce and test an APK.
- YouTube's oEmbed endpoint is only a screening check. A 404 is "unavailable/deleted/removed", not proof of deletion. 401/403 may mean private OR embedding restricted.
- The app waits 2 seconds between requests. Keep it open while checking. It can take about 2 hours for 3,434 videos, plus network delays.
- If the app is paused or closed, current in-memory results may be lost. Export results periodically where possible; the current simple version exports after the run.

Build:
1. Install Android Studio on a computer.
2. Open this project folder.
3. Let Android Studio install the Android Gradle plugin/SDK requested.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).
5. Install the generated APK on your Android phone.

Use:
1. Transfer penguinz0_202108_video_inventory.csv to your phone.
2. Open the app and choose the CSV.
3. Start checking; leave the app open and connected.
4. Export results CSV when complete.
