#!/usr/bin/env python3
"""Fetch public Penguinz0 upload metadata with yt-dlp and write a JS catalogue."""
import datetime as dt
import json
import subprocess
import sys
from pathlib import Path

CHANNEL_URL = "https://www.youtube.com/channel/UCq6VFHwMzcMXbuKyG7SQYIg/videos"
OUTPUT = Path("app/src/main/assets/catalogue.js")

def published_date(entry):
    raw = entry.get("upload_date") or entry.get("release_date")
    if isinstance(raw, str) and len(raw) == 8 and raw.isdigit():
        return f"{raw[:4]}-{raw[4:6]}-{raw[6:8]}T00:00:00Z"
    ts = entry.get("release_timestamp") or entry.get("timestamp")
    if ts:
        try:
            return dt.datetime.fromtimestamp(float(ts), tz=dt.timezone.utc).isoformat().replace("+00:00", "Z")
        except (ValueError, TypeError, OSError):
            pass
    return None

def main():
    cmd = [
        sys.executable, "-m", "yt_dlp",
        "--flat-playlist", "--dump-single-json",
        "--no-warnings", "--ignore-errors",
        CHANNEL_URL,
    ]
    print("Fetching public channel upload metadata (no YouTube Data API key)...", flush=True)
    result = subprocess.run(cmd, check=True, text=True, capture_output=True)
    data = json.loads(result.stdout)
    rows = []
    for entry in data.get("entries") or []:
        if not entry:
            continue
        video_id = entry.get("id")
        date = published_date(entry)
        if not video_id or len(video_id) != 11 or not date:
            continue
        rows.append({
            "id": video_id,
            "title": entry.get("title") or "Untitled",
            "publishedAt": date,
            "thumb": f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
        })
    unique = {row["id"]: row for row in rows}
    rows = list(unique.values())
    if not rows:
        raise SystemExit(
            "ERROR: yt-dlp returned no dated public uploads. YouTube may be "
            "blocking automated metadata requests, or the channel metadata "
            "format may have changed. Check the preceding yt-dlp diagnostics."
        )
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(
        "window.P0_CATALOGUE = " + json.dumps(rows, ensure_ascii=False, separators=(",", ":")) + ";\n",
        encoding="utf-8",
    )
    print(f"Wrote {len(rows)} dated public videos to {OUTPUT}", flush=True)

if __name__ == "__main__":
    main()
