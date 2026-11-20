// Cloudflare Worker: Penguinz0 live catalogue + secure GitHub Actions trigger.
// Required secret: GITHUB_TOKEN (fine-grained PAT: Actions read/write, Contents read/write).
// Optional vars: WORKER_URL (full https://...workers.dev URL for WebSub renewal).
const OWNER = "dannybully666-glitch";
const REPO = "joystick-app";
const WORKFLOW = "update-semantic-data.yml";
const PATH = "app/src/main/assets/catalogue.js";
const CHANNEL = "UCq6VFHwMzcMXbuKyG7SQYIg";
const HUB = "https://pubsubhubbub.appspot.com/subscribe";
const FEED = "https://www.youtube.com/feeds/videos.xml?channel_id=";
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
function xmlText(xml, tag) {
  const m = xml.match(new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${tag}>`, "i"));
  return m ? m[1].replace(/<!\[CDATA\[|\]\]>/g, "").replace(/&amp;/g,"&").replace(/&lt;/g,"<").replace(/&gt;/g,">").trim() : "";
}
function decodeCatalogue(source) {
  const m = source.match(/window\.P0_CATALOGUE\s*=\s*(\[[\s\S]*?\])\s*;/);
  if (!m) return [];
  try { return JSON.parse(m[1]); } catch { return []; }
}
async function github(env, path, init={}) {
  if (!env.GITHUB_TOKEN) throw new Error("Missing GITHUB_TOKEN Worker secret");
  const r = await fetch(`https://api.github.com/repos/${OWNER}/${REPO}/${path}`, {
    ...init, headers: { "Authorization": `Bearer ${env.GITHUB_TOKEN}`, "Accept":"application/vnd.github+json",
      "X-GitHub-Api-Version":"2022-11-28", "User-Agent":"Penguinz0-Live-Catalogue", ...(init.headers||{}) }
  });
  if (!r.ok) throw new Error(`GitHub API ${r.status}: ${(await r.text()).slice(0,500)}`);
  if (r.status === 204) return null;
  return r.json();
}
async function listRuns(env) {
  const data = await github(env, `actions/workflows/${WORKFLOW}/runs?branch=main&per_page=10`);
  return data.workflow_runs || [];
}
async function saveVideo(env, video) {
  const file = await github(env, `contents/${PATH}`);
  const source = atob(file.content.replace(/\n/g,""));
  const rows = decodeCatalogue(source);
  const i = rows.findIndex(x=>x.id===video.id);
  if(i>=0) rows[i]={...rows[i],...video}; else rows.unshift(video);
  rows.sort((a,b)=>Date.parse(b.publishedAt)-Date.parse(a.publishedAt));
  const body = `window.P0_CATALOGUE = ${JSON.stringify(rows)};\n`;
  await github(env, `contents/${PATH}`, {method:"PUT",headers:{"Content-Type":"application/json"},
    body:JSON.stringify({message:`Add new Penguinz0 upload ${video.id}`,content:btoa(unescape(encodeURIComponent(body))),sha:file.sha})});
}
async function subscribe(origin) {
  const callback = `${origin}/websub`;
  const body = new URLSearchParams({ "hub.mode":"subscribe", "hub.topic":FEED+CHANNEL,
    "hub.callback":callback, "hub.verify":"async", "hub.lease_seconds":"604800" });
  const r=await fetch(HUB,{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body});
  return r.status;
}
async function triggerDataUpdate(request, env) {
  const runs = await listRuns(env);
  const active = runs.find(r => ["queued", "in_progress", "requested", "waiting"].includes(r.status));
  if (active) return json({status:"busy", run_id:active.id, started_at:active.created_at,
    message:"A catalogue and semantic-index build is already running."}, 200, request);

  // Soft per-IP cooldown to prevent accidental repeated taps from flooding Actions.
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const rateKey = new Request(`https://p0-rate-limit.invalid/${encodeURIComponent(ip)}`);
  try {
    const cache = caches.default;
    if (await cache.match(rateKey)) return json({status:"rate_limited", retry_after_seconds:TRIGGER_COOLDOWN_SECONDS,
      message:"Please wait briefly before requesting another build."}, 429, request);
    await cache.put(rateKey, new Response("1", {headers:{"Cache-Control":`max-age=${TRIGGER_COOLDOWN_SECONDS}`}}));
  } catch (e) { /* cache is best-effort; GitHub concurrency still protects the workflow */ }

  const startedAt = new Date().toISOString();
  await github(env, `actions/workflows/${WORKFLOW}/dispatches`, {
    method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({ref:"main"})
  });
  return json({status:"accepted", started_at:startedAt,
    message:"GitHub Actions has been asked to rebuild the Penguinz0 catalogue and semantic index."}, 202, request);
}
async function dataUpdateStatus(request, env, url) {
  const runId = url.searchParams.get("run_id");
  if (runId && /^\d+$/.test(runId)) {
    const run = await github(env, `actions/runs/${runId}`);
    return json({status:run.status, conclusion:run.conclusion, run_id:run.id,
      html_url:run.html_url, created_at:run.created_at}, 200, request);
  }
  const since = Date.parse(url.searchParams.get("since") || "") || Date.now();
  const runs = await listRuns(env);
  const run = runs.filter(r => r.event === "workflow_dispatch" && r.head_branch === "main" &&
    Date.parse(r.created_at) >= since - 120000).sort((a,b)=>Date.parse(b.created_at)-Date.parse(a.created_at))[0];
  if (!run) return json({status:"queued", message:"Waiting for GitHub to register the workflow run."}, 200, request);
  return json({status:run.status, conclusion:run.conclusion, run_id:run.id,
    html_url:run.html_url, created_at:run.created_at}, 200, request);
}
export default {
 async fetch(request, env) {
  const url=new URL(request.url);
  if (request.method === "OPTIONS") return new Response(null, {status:204, headers:corsHeaders(request)});
  try {
   if(url.pathname==="/") return new Response("Penguinz0 live catalogue and GitHub Actions trigger are running.");
   if(url.pathname==="/trigger-data-update" && request.method==="POST") return await triggerDataUpdate(request,env);
   if(url.pathname==="/data-update-status" && request.method==="GET") return await dataUpdateStatus(request,env,url);
   if(url.pathname==="/catalogue" && request.method==="GET"){
    const f=await fetch(`https://raw.githubusercontent.com/${OWNER}/${REPO}/main/${PATH}?t=${Date.now()}`,{cf:{cacheTtl:0}});
    if(!f.ok) return json({error:`Catalogue fetch failed: HTTP ${f.status}`},502,request);
    const rows=decodeCatalogue(await f.text());
    return json({videos:rows},200,request);
   }
   if(url.pathname==="/websub" && request.method==="GET"){
    const mode=url.searchParams.get("hub.mode"),challenge=url.searchParams.get("hub.challenge");
    if(mode==="subscribe" && challenge) return new Response(challenge,{headers:{"Content-Type":"text/plain"}});
    return new Response("Bad verification", {status:403});
   }
   if(url.pathname==="/websub" && request.method==="POST"){
    const xml=await request.text();
    const id=xmlText(xml,"yt:videoId") || xmlText(xml,"videoId");
    const title=xmlText(xml,"title");
    const published=xmlText(xml,"published");
    if(!/^[\w-]{11}$/.test(id)||!published) return new Response("No video entry", {status:202});
    await saveVideo(env,{id,title:title||"Untitled",publishedAt:published,thumb:`https://i.ytimg.com/vi/${id}/mqdefault.jpg`});
    return new Response("OK");
   }
   return new Response("Not found",{status:404,headers:corsHeaders(request)});
  } catch (e) {
   return json({status:"error",message:e && e.message ? e.message : "Worker request failed"},500,request);
  }
 },
 async scheduled(event, env, ctx) {
   const base=env.WORKER_URL;
   if(base) ctx.waitUntil(subscribe(base));
 }
};
