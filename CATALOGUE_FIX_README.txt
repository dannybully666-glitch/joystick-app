CATALOGUE FIX — APPLY TO YOUR GITHUB REPOSITORY

Why it showed 0 videos:
The committed app code initializes an empty catalogue and only populates it
from a manually imported JSON file or an existing localStorage cache. There
was no automatic catalogue discovery.

This patch adds:
- scripts/fetch_catalogue.py, which runs yt-dlp against the public channel page.
- A GitHub Actions step that fetches public metadata before building the APK.
- app/src/main/assets/catalogue.js, generated at build time.
- App startup logic that loads the bundled catalogue on first launch.
- Existing embedded playback remains unchanged.

Apply the contents of this ZIP to your repo, preserving paths. Specifically
replace .github/workflows/android.yml and app/src/main/assets/index.html, and
add scripts/fetch_catalogue.py. Commit and push to main.

The workflow will fail clearly if yt-dlp cannot retrieve dated videos instead
of silently packaging an empty catalogue. This approach requires no YouTube
Data API key and no Google sign-in, but relies on YouTube's public page
remaining extractable; YouTube can rate-limit or block automated requests.
The build has not been run in your GitHub account. Embedded playback may still
show YouTube ads; this does not alter the player or guarantee ad-free playback.
