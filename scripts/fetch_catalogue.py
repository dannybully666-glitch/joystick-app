#!/usr/bin/env python3
"""Fetch the public Penguinz0 uploads catalogue, tolerating partial YouTube 403s."""
import datetime as dt
import json
import subprocess
import sys
import time
from pathlib import Path

CHANNEL_ID = "UCq6VFHwMzcMXbuKyG7SQYIg"
UPLOADS_PLAYLIST_ID = "UU" + CHANNEL_ID[2:]
UPLOADS_URL = f"https://www.youtube.com/playlist?list={UPLOADS_PLAYLIST_ID}"
OUTPUT = Path("app/src/main/assets/catalogue.js")


def as_iso_date(value):
    if not value:
        return None
    value = str(value)
    if len(value) == 8 and value.isdigit():
        return f"{value[:4]}-{value[4:6]}-{value[6:8]}T00:00:00Z"
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


def parse_json_output(stdout):
    """yt-dlp can return usable partial playlist JSON even when a later page fails."""
    if not stdout.strip():
        return None
    try:
        data = json.loads(stdout)
        return data if isinstance(data, dict) else None
    except json.JSONDecodeError:
        # In case diagnostic text precedes/follows the JSON, isolate the outer object.
        start, end = stdout.find("{"), stdout.rfind("}")
        if start >= 0 and end > start:
            try:
                data = json.loads(stdout[start:end + 1])
                return data if isinstance(data, dict) else None
            except json.JSONDecodeError:
                pass
    return None


def run_ytdlp():
    base = [
        sys.executable, "-m", "yt_dlp",
        "--flat-playlist",
        "--extractor-args", "youtubetab:approximate_date",
        "--dump-single-json",
        "--no-warnings",
        "--ignore-errors",
        "--no-abort-on-error",
        UPLOADS_URL,
    ]
    last_error = ""
    for attempt in range(1, 4):
        print(f"Fetching uploads playlist (attempt {attempt}/3): {UPLOADS_PLAYLIST_ID}", flush=True)
        result = subprocess.run(base, text=True, capture_output=True)
        data = parse_json_output(result.stdout)
        entries = (data or {}).get("entries") or []
        if entries:
            if result.returncode:
                print(
                    f"WARNING: yt-dlp exited {result.returncode} after returning "
                    f"{len(entries)} entries; using available partial results.",
                    flush=True,
                )
            return data, result.stderr
        last_error = result.stderr[-6000:]
        print(f"WARNING: no playlist entries returned (exit {result.returncode}).", flush=True)
        if last_error:
            print(last_error, file=sys.stderr)
        if attempt < 3:
            time.sleep(8 * attempt)
    return None, last_error


def read_existing_catalogue():
    if not OUTPUT.exists():
        return []
    text = OUTPUT.read_text(encoding="utf-8")
    prefix = "window.P0_CATALOGUE ="
    if not text.startswith(prefix):
        return []
    try:
        payload = text[len(prefix):].strip().removesuffix(";")
        rows = json.loads(payload)
        return rows if isinstance(rows, list) else []
    except (json.JSONDecodeError, ValueError):
        return []


def main():
    data, diagnostics = run_ytdlp()
    if data is None:
        existing = read_existing_catalogue()
        if existing:
            print(
                f"WARNING: YouTube blocked catalogue refresh. Keeping the "
                f"previous bundled catalogue ({len(existing)} videos) so the APK can still build.",
                flush=True,
            )
            return
        raise SystemExit(
            "YouTube returned no usable playlist data after retries, and no existing "
            "catalogue is available. This is a YouTube/runner HTTP 403, not an Android compile error."
        )

    entries = data.get("entries") or []
    rows, skipped_no_date, skipped_invalid_id = [], 0, 0
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

    unique = {}
    for row in rows:
        unique.setdefault(row["id"], row)
    rows = list(unique.values())

    print(f"Playlist entries received: {len(entries)}", flush=True)
    print(f"Valid dated videos: {len(rows)}", flush=True)
    print(f"Skipped without date: {skipped_no_date}", flush=True)
    print(f"Skipped invalid IDs: {skipped_invalid_id}", flush=True)

    if not rows:
        existing = read_existing_catalogue()
        if existing:
            print(
                f"WARNING: fetched data contained no dated entries; keeping existing "
                f"catalogue ({len(existing)} videos).",
                flush=True,
            )
            return
        if diagnostics:
            print(diagnostics[-6000:], file=sys.stderr)
        raise SystemExit("No usable dated entries and no previous catalogue to fall back to.")

    # Merge with the previous catalogue so a partial page-6 403 cannot erase older videos.
    previous = read_existing_catalogue()
    merged = {row["id"]: row for row in previous if isinstance(row, dict) and row.get("id")}
    merged.update({row["id"]: row for row in rows})
    final_rows = sorted(
        merged.values(),
        key=lambda row: row.get("publishedAt") or "",
        reverse=True,
    )

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(
        "window.P0_CATALOGUE = "
        + json.dumps(final_rows, ensure_ascii=False, separators=(",", ":"))
        + ";\n",
        encoding="utf-8",
    )
    print(
        f"SUCCESS: wrote {len(final_rows)} videos ({len(rows)} fetched this run; "
        f"{len(previous)} previously cached).",
        flush=True,
    )


if __name__ == "__main__":
    main()
