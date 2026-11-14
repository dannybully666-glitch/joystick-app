\
#!/usr/bin/env python3
"""Build one compact semantic embedding per Penguinz0 video title."""
import json
from pathlib import Path
import numpy as np
from sentence_transformers import SentenceTransformer

CATALOGUE = Path("app/src/main/assets/catalogue.js")
OUTPUT = Path("app/src/main/assets/semantic-index.json")
MODEL = "sentence-transformers/all-MiniLM-L6-v2"

def main():
    text = CATALOGUE.read_text(encoding="utf-8")
    prefix = "window.P0_CATALOGUE ="
    if not text.startswith(prefix):
        raise SystemExit("catalogue.js has an unexpected format")
    rows = json.loads(text[len(prefix):].strip().removesuffix(";"))
    rows = [r for r in rows if isinstance(r, dict) and r.get("id") and r.get("title")]
    if not rows:
        raise SystemExit("No videos available for semantic indexing")

    # Current catalogue supplies titles. If descriptions are added later, they are
    # automatically incorporated without changing the app.
    texts = [
        "Penguinz0 video. Title: " + str(r.get("title", "")) +
        (". Description: " + str(r.get("description", "")) if r.get("description") else "")
        for r in rows
    ]
    print(f"Loading embedding model {MODEL}")
    model = SentenceTransformer(MODEL)
    vectors = model.encode(
        texts, batch_size=64, show_progress_bar=True,
        normalize_embeddings=True, convert_to_numpy=True
    )
    # Quantized signed bytes reduce index size by about 4x vs float32.
    packed = np.clip(np.rint(vectors * 127), -127, 127).astype(np.int8)
    payload = {
        "model": "all-MiniLM-L6-v2",
        "version": 1,
        "ids": [r["id"] for r in rows],
        "vectors": packed.tolist()
    }
    OUTPUT.write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(rows)} semantic embeddings to {OUTPUT} ({OUTPUT.stat().st_size:,} bytes)")

if __name__ == "__main__":
    main()
