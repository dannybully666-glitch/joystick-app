#!/usr/bin/env python3
"""
Build the bundled Penguinz0 catalogue from the channel's public uploads playlist.

Key fix: flat YouTube playlist entries often omit upload_date. yt-dlp provides
the youtubetab:approximate_date extractor option to request approximate dates
for flat playlist entries. We also query the canonical uploads playlist
directly rather than relying on the channel /videos tab redirect.
"""
import datetime as dt
import json
import subprocess
import sys
from pathlib import Path

CHANNEL_ID = "UCq6VFHwMzcMXbuKyG7SQYIg"
UPLOADS_PLAYLIST_ID = "UU" + CHANNEL_ID[2:]
UPLOADS_URL = f"https://www.youtube.com/playlist?list={UPLOADS_PLAYLIST_ID}"
OUTPUT = Path("app/src/main/assets/catalogue.js")


def as_iso_date(value):
    """Convert yt-dlp date fields into an ISO UTC date string."""
    if not value:
        return None
    value = str(value)

    # yt-dlp upload_date is normally YYYYMMDD.
    if len(value) == 8 and value.isdigit():
        return f"{value[:4]}-{value[4:6]}-{value[6:8]}T00:00:00Z"

    # Some extractor/client combinations expose ISO date values.
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=dt.timezone.utc)
        return parsed.astimezone(dt.timezone.utc).isoformat().replace("+00:00", "Z")
    except ValueError:
        return None


def published_date(entry):
    for key in ("upload_date", "release_date", "release_timestamp", "timestamp"):
        value = entry.get(key)
        if not value:
            continue
        if key.endswith("timestamp"):
            try:
                return dt.datetime.fromtimestamp(
                    float(value), tz=dt.timezone.utc
                ).isoformat().replace("+00:00", "Z")
            except (ValueError, TypeError, OSError):
                continue
        converted = as_iso_date(value)
        if converted:
            return converted
    return None


def run_ytdlp():
    command = [
        sys.executable, "-m", "yt_dlp",
        "--flat-playlist",
        "--extractor-args", "youtubetab:approximate_date",
        "--dump-single-json",
        "--no-warnings",
        "--ignore-errors",
        UPLOADS_URL,
    ]
    print(f"Reading public uploads playlist: {UPLOADS_PLAYLIST_ID}", flush=True)
    print("Requesting approximate dates for flat playlist entries...", flush=True)

    result = subprocess.run(command, text=True, capture_output=True)
    if result.returncode != 0:
        print(result.stderr[-6000:], file=sys.stderr)
        raise SystemExit(f"yt-dlp failed with exit code {result.returncode}")

    try:
        return json.loads(result.stdout), result.stderr
    except json.JSONDecodeError as exc:
        print(result.stderr[-6000:], file=sys.stderr)
        raise SystemExit(f"yt-dlp did not return valid JSON: {exc}")


def main():
    data, diagnostics = run_ytdlp()
    entries = data.get("entries") or []
    rows = []
    skipped_no_date = 0
    skipped_invalid_id = 0

    for entry in entries:
        if not entry:
            continue
        video_id = entry.get("id")
        if not video_id or len(video_id) != 11:
            skipped_invalid_id += 1
            continue

        date = published_date(entry)
        if not date:
            skipped_no_date += 1
            continue

        rows.append({
            "id": video_id,
            "title": entry.get("title") or "Untitled",
            "publishedAt": date,
            "thumb": f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
        })

    # De-duplicate by ID, retaining the first occurrence.
    unique_rows = {}
    for row in rows:
        unique_rows.setdefault(row["id"], row)
    rows = list(unique_rows.values())

    print(f"Playlist entries received: {len(entries)}", flush=True)
    print(f"Valid dated videos: {len(rows)}", flush=True)
    print(f"Skipped because date was unavailable: {skipped_no_date}", flush=True)
    print(f"Skipped because ID was invalid: {skipped_invalid_id}", flush=True)

    if not rows:
        if diagnostics:
            print("yt-dlp diagnostics:\n" + diagnostics[-6000:], file=sys.stderr)
        raise SystemExit(
            "No dated video entries were returned. The approximate_date "
            "extractor option did not resolve the metadata. Check the yt-dlp "
            "diagnostics above; YouTube may be rate-limiting the GitHub runner "
            "or yt-dlp may need an extractor update."
        )

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(
        "window.P0_CATALOGUE = "
        + json.dumps(rows, ensure_ascii=False, separators=(",", ":"))
        + ";\n",
        encoding="utf-8",
    )
    print(f"SUCCESS: wrote {len(rows)} videos to {OUTPUT}", flush=True)


if __name__ == "__main__":
    main()
