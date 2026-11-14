// Cloudflare Worker: YouTube WebSub receiver + GitHub-hosted live catalogue.
// Required Worker secrets: GITHUB_TOKEN
// Optional vars: GITHUB_OWNER, GITHUB_REPO, CATALOGUE_PATH, CHANNEL_ID
const HUB = "https://pubsubhubbub.appspot.com/subscribe";
const FEED = "https://www.youtube.com/feeds/videos.xml?channel_id=";
const OWNER = "dannybully666-glitch";
const REPO = "joystick-app";
const PATH = "app/src/main/assets/catalogue.js";
const CHANNEL = "UCq6VFHwMzcMXbuKyG7SQYIg";

function xmlText(xml, tag) {
  const m = xml.match(new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${tag}>`, "i"));
  return m ? m[1].replace(/<!\[CDATA\[|\]\]>/g, "").replace(/&amp;/g,"&").replace(/&lt;/g,"<").replace(/&gt;/g,">").trim() : "";
}
function decodeCatalogue(source) {
  const m = source.match(/window\.P0_CATALOGUE\s*=\s*(\[[\s\S]*?\])\s*;/);
  if (!m) return [];
  try { return JSON.parse(m[1]); } catch { return []; }
}
async function gh(env, path, init={}) {
  const r = await fetch(`https://api.github.com/repos/${OWNER}/${REPO}/contents/${path}`, {
    ...init, headers: { "Authorization": `Bearer ${env.GITHUB_TOKEN}`, "Accept":"application/vnd.github+json",
      "X-GitHub-Api-Version":"2022-11-28", "User-Agent":"Penguinz0-Live-Catalogue", ...(init.headers||{}) }
  });
  if (!r.ok) throw new Error(`GitHub API ${r.status}: ${await r.text()}`);
  return r.json();
}
async function saveVideo(env, video) {
  const file = await gh(env, PATH);
  const source = atob(file.content.replace(/\n/g,""));
  const rows = decodeCatalogue(source);
  const i = rows.findIndex(x=>x.id===video.id);
  if(i>=0) rows[i]={...rows[i],...video}; else rows.unshift(video);
  rows.sort((a,b)=>Date.parse(b.publishedAt)-Date.parse(a.publishedAt));
  const body = `window.P0_CATALOGUE = ${JSON.stringify(rows)};\n`;
  await gh(env, PATH,{method:"PUT",headers:{"Content-Type":"application/json"},
    body:JSON.stringify({message:`Add new Penguinz0 upload ${video.id}`,content:btoa(unescape(encodeURIComponent(body))),sha:file.sha})});
}
async function subscribe(origin) {
  const callback = `${origin}/websub`;
  const body = new URLSearchParams({ "hub.mode":"subscribe", "hub.topic":FEED+CHANNEL,
    "hub.callback":callback, "hub.verify":"async", "hub.lease_seconds":"604800" });
  const r=await fetch(HUB,{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body});
  return r.status;
}
export default {
 async fetch(request, env) {
  const url=new URL(request.url);
  if(url.pathname==="/") return new Response("Penguinz0 live catalogue webhook is running.");
  if(url.pathname==="/catalogue" && request.method==="GET"){
    const f=await fetch(`https://raw.githubusercontent.com/${OWNER}/${REPO}/main/${PATH}?t=${Date.now()}`,{cf:{cacheTtl:0}});
    const js=await f.text();const rows=decodeCatalogue(js);
    return new Response(JSON.stringify({videos:rows}),{headers:{"Content-Type":"application/json","Access-Control-Allow-Origin":"*","Cache-Control":"no-store"}});
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
    if(!/^[\\w-]{11}$/.test(id)||!published) return new Response("No video entry", {status:202});
    await saveVideo(env,{id,title:title||"Untitled",publishedAt:published,thumb:`https://i.ytimg.com/vi/${id}/mqdefault.jpg`});
    return new Response("OK");
  }
  return new Response("Not found",{status:404});
 },
 async scheduled(event, env, ctx) {
   // Re-subscribe every day; YouTube WebSub leases expire.
   const base=env.WORKER_URL;
   if(base) await subscribe(base);
 }
};
