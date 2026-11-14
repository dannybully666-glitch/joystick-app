#!/usr/bin/env python3
"""Incrementally build one compact embedding per Penguinz0 video title."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np

MODEL = "sentence-transformers/all-MiniLM-L6-v2"
PREFIX = "window.P0_CATALOGUE ="

def load_catalogue(path):
    raw = path.read_text(encoding="utf-8")
    if not raw.startswith(PREFIX):
        raise SystemExit(f"Unexpected catalogue format: {path}")
    rows = json.loads(raw[len(PREFIX):].strip().removesuffix(";"))
    return [r for r in rows if isinstance(r, dict) and r.get("id") and r.get("title")]

def source_text(row):
    return "Penguinz0 video. Title: " + str(row.get("title", "")) + (
        ". Description: " + str(row.get("description", "")) if row.get("description") else ""
    )

def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalogue", default="app/src/main/assets/catalogue.js")
    ap.add_argument("--previous-index", default="")
    ap.add_argument("--output", default="app/src/main/assets/semantic-index.json")
    ap.add_argument("--check-only", action="store_true", help="Only report whether new embeddings are needed")
    args = ap.parse_args()

    rows = load_catalogue(Path(args.catalogue))
    if not rows:
        raise SystemExit("No videos available for semantic indexing")

    old = {}
    if args.previous_index and Path(args.previous_index).exists():
        try:
            data = json.loads(Path(args.previous_index).read_text(encoding="utf-8"))
            ids, vectors = data.get("ids", []), data.get("vectors", [])
            hashes = data.get("hashes", [])
            for i, (vid, vec) in enumerate(zip(ids, vectors)):
                old[vid] = (vec, hashes[i] if i < len(hashes) else "")
        except Exception as e:
            print(f"WARNING: previous index unreadable; rebuilding: {e}")

    ids, vectors, hashes = [], [], []
    pending_rows, pending_positions = [], []
    for row in rows:
        text = source_text(row)
        h = digest(text)
        vid = row["id"]
        ids.append(vid); hashes.append(h)
        previous = old.get(vid)
        if previous and previous[1] == h:
            vectors.append(previous[0])
        else:
            vectors.append(None)
            pending_positions.append(len(vectors) - 1)
            pending_rows.append(text)

    if args.check_only:
        print(f"needs_embedding={'true' if pending_rows else 'false'}")
        return

    if pending_rows:
        print(f"Generating {len(pending_rows)} new/changed embeddings; reusing {len(rows)-len(pending_rows)} existing.")
        from sentence_transformers import SentenceTransformer
        model = SentenceTransformer(MODEL)
        encoded = model.encode(
            pending_rows, batch_size=64, show_progress_bar=True,
            normalize_embeddings=True, convert_to_numpy=True
        )
        packed = np.clip(np.rint(encoded * 127), -127, 127).astype(np.int8)
        for pos, vec in zip(pending_positions, packed):
            vectors[pos] = vec.tolist()
    else:
        print("No new or changed videos; all embeddings reused.")

    payload = {
        "model": "all-MiniLM-L6-v2",
        "version": 2,
        "ids": ids,
        "hashes": hashes,
        "vectors": vectors
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(ids)} embeddings to {output} ({output.stat().st_size:,} bytes)")

if __name__ == "__main__":
    main()
