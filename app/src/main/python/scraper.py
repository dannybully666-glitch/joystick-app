import csv
import os
import time
from yt_dlp import YoutubeDL

FIELDS = [
    "title", "description", "id", "url", "upload_date", "release_date",
    "duration", "view_count", "like_count", "uploader", "uploader_id",
    "channel", "channel_id", "categories", "tags", "availability",
    "metadata_status"
]

def _text(value):
    if value is None:
        return ""
    if isinstance(value, (list, tuple)):
        return " | ".join(str(x) for x in value)
    return str(value).replace("\r", " ").strip()

def scrape_channel(channel_url, app_data_dir):
    channel_url = channel_url.strip()
    if not channel_url:
        raise ValueError("Enter a YouTube channel URL.")

    if "/videos" not in channel_url:
        channel_url = channel_url.rstrip("/") + "/videos"

    output_path = os.path.join(app_data_dir, "mapmonkeysdb_video_inventory.csv")
    progress_path = os.path.join(app_data_dir, "scrape_progress.txt")

    # First enumerate the channel's available video entries.
    list_opts = {
        "skip_download": True,
        "quiet": True,
        "no_warnings": True,
        "ignoreerrors": True,
        "extract_flat": True,
        "lazy_playlist": False,
        "socket_timeout": 30,
        "retries": 2,
        "extractor_retries": 2,
    }
    with YoutubeDL(list_opts) as ydl:
        listing = ydl.extract_info(channel_url, download=False)

    if not listing:
        raise RuntimeError("YouTube did not return a channel listing.")

    entries = listing.get("entries") or []
    videos = []
    seen = set()
    for entry in entries:
        if not entry:
            continue
        vid = entry.get("id")
        if vid and vid not in seen:
            seen.add(vid)
            videos.append(entry)

    if not videos:
        raise RuntimeError(
            "No videos were found. YouTube may have blocked the request, "
            "or the channel listing format may have changed."
        )

    completed = set()
    file_exists = os.path.exists(output_path) and os.path.getsize(output_path) > 0
    if file_exists:
        try:
            with open(output_path, "r", newline="", encoding="utf-8-sig") as existing:
                for row in csv.DictReader(existing):
                    if row.get("id"):
                        completed.add(row["id"])
        except Exception:
            completed = set()
            file_exists = False

    todo = [entry for entry in videos if entry.get("id") not in completed]
    total = len(videos)
    already = len(completed.intersection(seen))

    with open(progress_path, "w", encoding="utf-8") as p:
        p.write(f"Found {total} channel entries. {already} already saved; {len(todo)} remaining.")

    mode = "a" if file_exists else "w"
    with open(output_path, mode, newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        if mode == "w":
            writer.writeheader()
            f.flush()

        detail_opts = {
            "skip_download": True,
            "quiet": True,
            "no_warnings": True,
            "ignoreerrors": True,
            "noplaylist": True,
            "socket_timeout": 30,
            "retries": 2,
            "extractor_retries": 2,
        }

        for index, entry in enumerate(todo, start=1):
            vid = entry.get("id")
            url = entry.get("url") or f"https://www.youtube.com/watch?v={vid}"
            if not url.startswith("http"):
                url = f"https://www.youtube.com/watch?v={vid}"

            row = {key: "" for key in FIELDS}
            row["id"] = vid
            row["url"] = f"https://www.youtube.com/watch?v={vid}"
            row["title"] = _text(entry.get("title"))

            try:
                with YoutubeDL(detail_opts) as ydl:
                    info = ydl.extract_info(row["url"], download=False)
                if info:
                    for key in FIELDS:
                        if key in info:
                            row[key] = _text(info.get(key))
                    row["id"] = vid
                    row["url"] = f"https://www.youtube.com/watch?v={vid}"
                    row["metadata_status"] = "retrieved"
                else:
                    row["metadata_status"] = "unavailable or extraction failed"
            except Exception as exc:
                row["metadata_status"] = "error: " + type(exc).__name__ + ": " + str(exc)[:250]

            writer.writerow(row)
            f.flush()

            if index % 5 == 0 or index == len(todo):
                done_now = already + index
                title = row.get("title") or vid
                with open(progress_path, "w", encoding="utf-8") as p:
                    p.write(f"Processed {done_now}/{total}\n{title[:100]}")

            # Be polite to YouTube and reduce the chance of rate limiting.
            time.sleep(0.8)

    with open(progress_path, "w", encoding="utf-8") as p:
        p.write(f"Finished: {total} channel entries. CSV saved.")
    return output_path
