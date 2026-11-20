// Cloudflare Worker: Penguinz0 WebSub receiver + secure GitHub Actions trigger.
// Required secret: GITHUB_TOKEN (fine-grained PAT: Actions read/write, Contents read/write).
// Required variable: WORKER_URL (this Worker's full workers.dev URL; used for WebSub renewal).
const OWNER = "dannybully666-glitch";
const REPO = "joystick-app";
const WORKFLOW = "update-semantic-data.yml";
const PATH = "app/src/main/assets/catalogue.js";
const CHANNEL = "UCq6VFHwMzcMXbuKyG7SQYIg";
const HUB = "https://pubsubhubbub.appspot.com/subscribe";
const FEED = `https://www.youtube.com/feeds/videos.xml?channel_id=${CHANNEL}`;
const APP_ORIGINS = new Set(["https://appassets.androidplatform.net", "https://localhost"]);
const TRIGGER_COOLDOWN_SECONDS = 90;

function corsHeaders(request) {
  const origin = request.headers.get("Origin") || "";
  const allowed = APP_ORIGINS.has(origin) ? origin : "https://appassets.androidplatform.net";
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin"
  };
}
function json(data, status, request) {
  return new Response(JSON.stringify(data), { status: status || 200, headers: {
    ...corsHeaders(request), "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store"
  }});
}
function decodeBase64Utf8(value) {
  const binary = atob(value.replace(/\s/g, ""));
  const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}
function encodeBase64Utf8(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}
function decodeCatalogue(source) {
  const m = source.match(/window\.P0_CATALOGUE\s*=\s*(\[[\s\S]*\])\s*;?\s*$/);
  if (!m) return [];
  try {
    const rows = JSON.parse(m[1]);
    return Array.isArray(rows) ? rows : [];
  } catch { return []; }
}
function decodeXml(value) {
  return value.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'").trim();
}
function entryTag(entry, tag) {
  const escaped = tag.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = entry.match(new RegExp(`<${escaped}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${escaped}>`, "i"));
  return match ? decodeXml(match[1]) : "";
}
async function github(env, path, init = {}) {
  if (!env.GITHUB_TOKEN) throw new Error("Missing GITHUB_TOKEN Worker secret");
  const response = await fetch(`https://api.github.com/repos/${OWNER}/${REPO}/${path}`, {
    ...init,
    headers: {
      "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
      "Accept": "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "Penguinz0-Live-Catalogue",
      ...(init.headers || {})
    }
  });
  if (!response.ok) throw new Error(`GitHub API ${response.status}: ${(await response.text()).slice(0, 500)}`);
  if (response.status === 204) return null;
  return response.json();
}
async function listRuns(env) {
  const data = await github(env, `actions/workflows/${WORKFLOW}/runs?branch=main&per_page=10`);
  return data.workflow_runs || [];
}
async function dispatchBuild(env) {
  await github(env, `actions/workflows/${WORKFLOW}/dispatches`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ref: "main" })
  });
}
async function upsertVideo(env, video) {
  // Retry GitHub's SHA conflict if two WebSub deliveries arrive together.
  for (let attempt = 1; attempt <= 3; attempt++) {
    const file = await github(env, `contents/${PATH}?ref=main`);
    const source = decodeBase64Utf8(file.content);
    const rows = decodeCatalogue(source);
    if (!rows.length) throw new Error("The main-branch catalogue is missing or unreadable; refusing to overwrite it.");

    const index = rows.findIndex(row => row && row.id === video.id);
    let changed = false;
    if (index < 0) {
      rows.unshift(video);
      changed = true;
    } else {
      const old = rows[index];
      const merged = { ...old, ...video };
      // Keep richer existing fields; only update the WebSub fields when they differ.
      if (old.title && video.title === "Untitled") merged.title = old.title;
      if (JSON.stringify(old) !== JSON.stringify(merged)) {
        rows[index] = merged;
        changed = true;
      }
    }
    if (!changed) return { changed: false, count: rows.length };

    rows.sort((a, b) => Date.parse(b.publishedAt || "") - Date.parse(a.publishedAt || ""));
    const body = `window.P0_CATALOGUE = ${JSON.stringify(rows)};\n`;
    try {
      await github(env, `contents/${PATH}`, {
        method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message: `WebSub: add Penguinz0 upload ${video.id}`,
          content: encodeBase64Utf8(body),
          sha: file.sha,
          branch: "main"
        })
      });
      return { changed: true, count: rows.length };
    } catch (error) {
      if (!String(error.message).includes("GitHub API 409") || attempt === 3) throw error;
    }
  }
  throw new Error("Could not safely update catalogue after concurrent WebSub deliveries.");
}
async function subscribe(origin) {
  const callback = `${origin.replace(/\/$/, "")}/websub`;
  const body = new URLSearchParams({
    "hub.mode": "subscribe", "hub.topic": FEED, "hub.callback": callback,
    "hub.verify": "async", "hub.lease_seconds": "604800"
  });
  const response = await fetch(HUB, {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body
  });
  if (!response.ok) throw new Error(`WebSub subscription request failed: HTTP ${response.status}`);
  return response.status;
}
async function triggerDataUpdate(request, env) {
  const runs = await listRuns(env);
  const active = runs.find(run => ["queued", "in_progress", "requested", "waiting"].includes(run.status));
  if (active) return json({ status: "busy", run_id: active.id, started_at: active.created_at,
    message: "A catalogue and semantic-index build is already running." }, 200, request);

  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const rateKey = new Request(`https://p0-rate-limit.invalid/${encodeURIComponent(ip)}`);
  try {
    const cache = caches.default;
    if (await cache.match(rateKey)) return json({ status: "rate_limited", retry_after_seconds: TRIGGER_COOLDOWN_SECONDS,
      message: "Please wait before requesting another manual build." }, 429, request);
    await cache.put(rateKey, new Response("1", { headers: { "Cache-Control": `max-age=${TRIGGER_COOLDOWN_SECONDS}` } }));
  } catch (error) { /* GitHub concurrency remains the fallback protection. */ }

  const startedAt = new Date().toISOString();
  await dispatchBuild(env);
  return json({ status: "accepted", started_at: startedAt,
    message: "GitHub Actions has been asked to rebuild the Penguinz0 catalogue and semantic index." }, 202, request);
}
async function dataUpdateStatus(request, env, url) {
  const runId = url.searchParams.get("run_id");
  if (runId && /^\d+$/.test(runId)) {
    const run = await github(env, `actions/runs/${runId}`);
    return json({ status: run.status, conclusion: run.conclusion, run_id: run.id,
      html_url: run.html_url, created_at: run.created_at }, 200, request);
  }
  const since = Date.parse(url.searchParams.get("since") || "") || Date.now();
  const runs = await listRuns(env);
  const run = runs.filter(item => item.event === "workflow_dispatch" && item.head_branch === "main" &&
    Date.parse(item.created_at) >= since - 120000)
    .sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))[0];
  if (!run) return json({ status: "queued", message: "Waiting for GitHub to register the workflow run." }, 200, request);
  return json({ status: run.status, conclusion: run.conclusion, run_id: run.id,
    html_url: run.html_url, created_at: run.created_at }, 200, request);
}
async function handleWebSub(request, env, url) {
  if (request.method === "GET") {
    const mode = url.searchParams.get("hub.mode");
    const topic = url.searchParams.get("hub.topic");
    const challenge = url.searchParams.get("hub.challenge");
    if (mode === "subscribe" && topic === FEED && challenge) {
      return new Response(challenge, { headers: { "Content-Type": "text/plain", "Cache-Control": "no-store" } });
    }
    return new Response("Invalid WebSub verification", { status: 403 });
  }

  const xml = await request.text();
  const entryMatch = xml.match(/<entry(?:\s[^>]*)?>([\s\S]*?)<\/entry>/i);
  if (!entryMatch) return new Response("No entry", { status: 202 });
  const entry = entryMatch[1];
  const channelId = entryTag(entry, "yt:channelId");
  const id = entryTag(entry, "yt:videoId");
  const title = entryTag(entry, "title");
  const published = entryTag(entry, "published");
  if (channelId !== CHANNEL || !/^[\w-]{11}$/.test(id) || !published) {
    return new Response("Ignored non-matching or incomplete notification", { status: 202 });
  }

  const result = await upsertVideo(env, {
    id, title: title || "Untitled", publishedAt: published,
    thumb: `https://i.ytimg.com/vi/${id}/mqdefault.jpg`
  });

  // Deduplicate hub retries, but do not mark the event handled until dispatch succeeds.
  // If GitHub is temporarily unavailable, a retried WebSub delivery can try again.
  const eventKey = new Request(`https://p0-websub-event.invalid/${id}`);
  const cache = caches.default;
  if (await cache.match(eventKey)) {
    return new Response(result.changed ? "Catalogue updated; build already queued" : "Already catalogued and build already queued", { status: 200 });
  }
  // WebSub uses workflow_dispatch explicitly. This avoids depending on push-event
  // behaviour and lets the workflow run even if another build is already active.
  await dispatchBuild(env);
  try {
    await cache.put(eventKey, new Response("queued", { headers: { "Cache-Control": "max-age=900" } }));
  } catch (error) { /* The workflow concurrency group still prevents overlapping builds. */ }
  return new Response(result.changed ? "New video queued for catalogue and semantic-index build" : "Existing video queued for a recovery build", { status: 202 });
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(request) });
    try {
      if (url.pathname === "/") return new Response("Penguinz0 WebSub catalogue worker is running.");
      if (url.pathname === "/trigger-data-update" && request.method === "POST") return await triggerDataUpdate(request, env);
      if (url.pathname === "/data-update-status" && request.method === "GET") return await dataUpdateStatus(request, env, url);
      if (url.pathname === "/catalogue" && request.method === "GET") {
        const response = await fetch(`https://raw.githubusercontent.com/${OWNER}/${REPO}/main/${PATH}?t=${Date.now()}`, { cf: { cacheTtl: 0 } });
        if (!response.ok) return json({ error: `Catalogue fetch failed: HTTP ${response.status}` }, 502, request);
        return json({ videos: decodeCatalogue(await response.text()) }, 200, request);
      }
      if (url.pathname === "/websub" && (request.method === "GET" || request.method === "POST")) {
        return await handleWebSub(request, env, url);
      }
      if (url.pathname === "/websub-status" && request.method === "GET") {
        return json({ channel_id: CHANNEL, topic: FEED, callback: `${env.WORKER_URL || url.origin}/websub`,
          renewal_cron_expected: "0 */12 * * *", note: "This reports configuration, not the hub's live subscription state." }, 200, request);
      }
      return new Response("Not found", { status: 404, headers: corsHeaders(request) });
    } catch (error) {
      return json({ status: "error", message: error && error.message ? error.message : "Worker request failed" }, 500, request);
    }
  },
  async scheduled(event, env, ctx) {
    if (env.WORKER_URL) ctx.waitUntil(subscribe(env.WORKER_URL));
  }
};
