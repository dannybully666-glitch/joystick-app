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

function base64Url(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function utf8Base64Url(value) { return base64Url(new TextEncoder().encode(value)); }
async function getFcmAccessToken(env) {
  if (!env.FCM_SERVICE_ACCOUNT_JSON) throw new Error("Missing FCM_SERVICE_ACCOUNT_JSON Worker secret");
  const account = JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON);
  if (!account.project_id || !account.client_email || !account.private_key) throw new Error("FCM service account JSON is incomplete");
  const now = Math.floor(Date.now() / 1000);
  const header = utf8Base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = utf8Base64Url(JSON.stringify({ iss: account.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud: account.token_uri || "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 }));
  const unsigned = `${header}.${claim}`;
  const pem = account.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = Uint8Array.from(atob(pem), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", binary, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const assertion = `${unsigned}.${base64Url(signature)}`;
  const form = new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion });
  const response = await fetch(account.token_uri || "https://oauth2.googleapis.com/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: form });
  if (!response.ok) throw new Error(`FCM OAuth ${response.status}: ${(await response.text()).slice(0, 300)}`);
  return (await response.json()).access_token;
}
async function fcmDeviceKey(token) {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)));
  return "device:" + Array.from(digest, b => b.toString(16).padStart(2, "0")).join("");
}

async function registerFcmDevice(request, env) {
  if (!env.FCM_DEVICES) return json({ error: "FCM_DEVICES KV binding is not configured" }, 503, request);
  const data = await request.json();
  const token = typeof data.token === "string" ? data.token.trim() : "";
  if (token.length < 40 || token.length > 4096 || !/^[A-Za-z0-9:_-]+$/.test(token)) {
    return json({ error: "Invalid FCM token" }, 400, request);
  }
  const key = await fcmDeviceKey(token);
  await env.FCM_DEVICES.put(key, token);
  return json({ status: "registered", message: "This app installation is registered for upload notifications." }, 200, request);
}

async function listFcmDevices(env) {
  const tokens = new Set();
  let cursor;
  do {
    const page = await env.FCM_DEVICES.list({ prefix: "device:", cursor, limit: 1000 });
    for (const item of page.keys || []) {
      const token = await env.FCM_DEVICES.get(item.name);
      if (token) tokens.add(token);
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  // Backward compatibility: include a token registered by the previous single-device version.
  const legacyToken = await env.FCM_DEVICES.get("primary_device_token");
  if (legacyToken) tokens.add(legacyToken);
  return [...tokens];
}

async function sendUploadPush(env, video) {
  if (!env.FCM_DEVICES || !env.FCM_SERVICE_ACCOUNT_JSON) {
    console.warn("Upload push skipped: FCM_DEVICES binding or FCM_SERVICE_ACCOUNT_JSON secret is missing");
    return;
  }

  const tokens = await listFcmDevices(env);
  if (!tokens.length) {
    console.warn("Upload push skipped: no registered device tokens");
    return;
  }

  const account = JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON);
  const accessToken = await getFcmAccessToken(env);
  let sent = 0;
  let removed = 0;
  let failed = 0;

  for (const token of tokens) {
    const response = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
      method: "POST",
      headers: { "Authorization": `Bearer ${accessToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: {
          token,
          notification: {
            title: "New Penguinz0 upload",
            body: `“${video.title || "New video"}” — semantic build started.`
          },
          data: {
            videoId: video.id,
            title: video.title || "New video",
            event: "websub_upload_build_started"
          },
          android: {
            priority: "high",
            notification: { channel_id: "penguinz0_uploads", sound: "default" }
          }
        }
      })
    });

    if (response.ok) {
      sent++;
      continue;
    }

    const errorText = await response.text();
    let errorData = {};
    try { errorData = JSON.parse(errorText); } catch {}
    const details = JSON.stringify(errorData.error && errorData.error.details || []);
    const errorCode = errorData.error && errorData.error.status || "";
    const invalidToken =
      errorCode === "NOT_FOUND" ||
      details.includes("UNREGISTERED") ||
      details.includes("INVALID_ARGUMENT") && /registration token/i.test(errorText);

    if (invalidToken) {
      await env.FCM_DEVICES.delete(await fcmDeviceKey(token));
      if (await env.FCM_DEVICES.get("primary_device_token") === token) {
        await env.FCM_DEVICES.delete("primary_device_token");
      }
      removed++;
      console.warn("Removed invalid FCM device token");
    } else {
      failed++;
      console.error("FCM send failed for one device", response.status, errorText.slice(0, 300));
    }
  }

  console.log("FCM upload notification delivery", JSON.stringify({
    registered_devices: tokens.length, sent, removed_invalid: removed, failed
  }));
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
  console.log("WebSub hub request starting", JSON.stringify({ callback, topic: FEED, mode: "subscribe", verify: "async", requested_lease_seconds: 604800 }));
  const response = await fetch(HUB, {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body
  });
  const hubBody = (await response.text()).slice(0, 500);
  console.log("WebSub hub response", JSON.stringify({ status: response.status, ok: response.ok, body: hubBody }));
  if (!response.ok) throw new Error(`WebSub subscription request failed: HTTP ${response.status}: ${hubBody}`);
  return { hub_status: response.status, hub_response_body: hubBody, callback, topic: FEED, requested_lease_seconds: 604800 };
}
const WEBSUB_TOOLS_HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Penguinz0 WebSub Tools</title>
<style>body{margin:0;background:#101510;color:#edf3ed;font:16px system-ui,sans-serif;padding:22px}main{max-width:620px;margin:auto}h1{font-size:24px}p{color:#b8c4b8;line-height:1.5}.card{background:#1b261e;border:1px solid #35483a;border-radius:14px;padding:16px;margin:14px 0}label{display:block;margin:10px 0 6px}input{box-sizing:border-box;width:100%;padding:13px;border-radius:9px;border:1px solid #526657;background:#0c130e;color:white;font-size:16px}button{padding:13px 17px;border:0;border-radius:9px;background:#b4d5a7;color:#102010;font-weight:700;font-size:16px;margin:10px 8px 0 0}button.secondary{background:#344438;color:#eff7ec}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#0c120e;padding:12px;border-radius:8px;font-size:13px}.muted{font-size:13px;color:#9eada0}.ok{color:#a8e6a1}.warn{color:#ffd28a}</style></head>
<body><main><h1>Penguinz0 WebSub tools</h1><p>Check the Worker's public configuration and manually request a WebSub subscription. The token stays in this page's memory and is sent only to this Worker over HTTPS.</p>
<section class="card"><h2>Worker status</h2><button class="secondary" id="refresh">Check status</button><pre id="status">Not checked yet.</pre></section>
<section class="card"><h2>Manual subscription test</h2><label for="token">WEBSUB_ADMIN_TOKEN</label><input id="token" type="password" autocomplete="off" placeholder="Paste your Cloudflare secret"><button id="subscribe">Subscribe now</button><p class="muted">This sends a POST to /websub-subscribe. A 202 means the hub accepted the request, not that callback verification completed.</p><pre id="result">No request sent.</pre></section>
<section class="card"><h2>What to verify next</h2><p>After a 202 response, open Cloudflare → Worker → Observability → Logs. Find <b>WebSub verification callback</b>. A successful verification should report <b>accepted: true</b> and <b>response_status: 200</b>. This page cannot read Cloudflare's private invocation logs, so it will not pretend that a subscription is active based only on a 202.</p><p class="muted">Do not share your token or screenshots that expose it.</p></section></main>
<script>
const $=id=>document.getElementById(id);const base=location.origin;
$('refresh').onclick=async()=>{const el=$('status');el.textContent='Checking…';try{const r=await fetch(base+'/websub-status',{cache:'no-store'});const t=await r.text();el.textContent='HTTP '+r.status+'\\n'+t;}catch(e){el.textContent='Could not reach Worker: '+e.message;}};
$('subscribe').onclick=async()=>{const token=$('token').value.trim();const el=$('result');if(!token){el.textContent='Enter your WEBSUB_ADMIN_TOKEN first.';return;}el.textContent='Sending request…';$('subscribe').disabled=true;try{const r=await fetch(base+'/websub-subscribe',{method:'POST',headers:{'Authorization':'Bearer '+token}});const t=await r.text();el.textContent='HTTP '+r.status+'\\n'+t;}catch(e){el.textContent='Request failed: '+e.message;}finally{$('subscribe').disabled=false;}};
$('refresh').click();
</script></body></html>`;

async function manualWebSubSubscribe(request, env) {
  if (!env.WEBSUB_ADMIN_TOKEN) {
    return json({ status: "configuration_error", message: "Set the WEBSUB_ADMIN_TOKEN secret in Cloudflare Worker Production settings first." }, 503, request);
  }
  const authorization = request.headers.get("Authorization") || "";
  const supplied = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
  if (!supplied || supplied !== env.WEBSUB_ADMIN_TOKEN) {
    return json({ status: "unauthorized", message: "A valid Bearer token is required." }, 401, request);
  }
  const origin = (env.WORKER_URL || new URL(request.url).origin).replace(/\/$/, "");
  if (!/^https:\/\/[a-z0-9.-]+\.workers\.dev$/i.test(origin)) {
    return json({ status: "configuration_error", message: "WORKER_URL must be the deployed workers.dev URL." }, 500, request);
  }
  const result = await subscribe(origin);
  console.log("Manual WebSub subscription requested", JSON.stringify(result));
  return json({ status: "request_accepted", ...result,
    message: "The hub accepted the subscription request. Verification happens asynchronously; this response alone does not prove the subscription is active. Check Worker logs for the incoming GET /websub verification request." }, 202, request);
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
    const leaseSeconds = url.searchParams.get("hub.lease_seconds");
    const valid = mode === "subscribe" && topic === FEED && !!challenge;
    console.log("WebSub verification callback", JSON.stringify({
      method: "GET", mode: mode || null, topic_matches: topic === FEED,
      challenge_present: !!challenge, lease_seconds: leaseSeconds || null,
      accepted: valid, response_status: valid ? 200 : 403
    }));
    if (valid) {
      return new Response(challenge, { status: 200, headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" } });
    }
    return new Response("Invalid WebSub verification", { status: 403 });
  }

  const xml = await request.text();
  console.log("WebSub notification received", JSON.stringify({ method: "POST", content_type: request.headers.get("content-type"), body_bytes: new TextEncoder().encode(xml).length }));
  const entryMatch = xml.match(/<entry(?:\s[^>]*)?>([\s\S]*?)<\/entry>/i);
  if (!entryMatch) {
    console.log("WebSub notification ignored", JSON.stringify({ reason: "no_entry" }));
    return new Response("No entry", { status: 202 });
  }
  const entry = entryMatch[1];
  const channelId = entryTag(entry, "yt:channelId");
  const id = entryTag(entry, "yt:videoId");
  const title = entryTag(entry, "title");
  const published = entryTag(entry, "published");
  if (channelId !== CHANNEL || !/^[\w-]{11}$/.test(id) || !published) {
    console.log("WebSub notification ignored", JSON.stringify({ reason: "channel_or_fields_mismatch", channel_matches: channelId === CHANNEL, video_id_present: !!id, video_id_valid: /^[\w-]{11}$/.test(id), published_present: !!published }));
    return new Response("Ignored non-matching or incomplete notification", { status: 202 });
  }
  console.log("WebSub video notification parsed", JSON.stringify({ channel_matches: true, video_id: id, title_present: !!title, published }));

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
  try { await sendUploadPush(env, { id, title: title || "New video" }); }
  catch (pushError) { console.error("WebSub build dispatched but FCM notification failed", pushError && pushError.message ? pushError.message : String(pushError)); }
  console.log("WebSub notification queued GitHub build", JSON.stringify({ video_id: id, catalogue_changed: result.changed }));
  try {
    await cache.put(eventKey, new Response("queued", { headers: { "Cache-Control": "max-age=900" } }));
  } catch (error) { /* The workflow concurrency group still prevents overlapping builds. */ }
  return new Response(result.changed ? "New video queued for catalogue and semantic-index build" : "Existing video queued for a recovery build", { status: 202 });
}
export default {
  // Automatic WebSub lease renewal. This runs every 12 hours via wrangler.toml.
  // The manual /websub-subscribe endpoint remains available for testing.
  async scheduled(controller, env, ctx) {
    const origin = (env.WORKER_URL || "").replace(/\/$/, "");
    if (!/^https:\/\/[a-z0-9.-]+\.workers\.dev$/i.test(origin)) {
      console.error("Scheduled WebSub renewal skipped: WORKER_URL is missing or invalid.");
      return;
    }
    ctx.waitUntil(subscribe(origin).then(result => {
      console.log("Scheduled WebSub renewal requested", JSON.stringify(result));
    }).catch(error => {
      console.error("Scheduled WebSub renewal failed", error && error.message ? error.message : String(error));
    }));
  },
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(request) });
    try {
      if (url.pathname === "/") return new Response("Penguinz0 WebSub catalogue worker is running.");
      if (url.pathname === "/fcm/register" && request.method === "POST") return await registerFcmDevice(request, env);
      if (url.pathname === "/websub-tools" && request.method === "GET") return new Response(WEBSUB_TOOLS_HTML, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
      if (url.pathname === "/trigger-data-update" && request.method === "POST") return await triggerDataUpdate(request, env);
      if (url.pathname === "/websub-subscribe" && request.method === "POST") return await manualWebSubSubscribe(request, env);
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
          manual_subscribe_endpoint: `${url.origin}/websub-subscribe`,
          renewal: "automatic every 12 hours via Cloudflare Cron Trigger; manual endpoint available for testing",
          note: "This reports configuration, not the hub's live subscription state." }, 200, request);
      }
      return new Response("Not found", { status: 404, headers: corsHeaders(request) });
    } catch (error) {
      return json({ status: "error", message: error && error.message ? error.message : "Worker request failed" }, 500, request);
    }
  }
};
