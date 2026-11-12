// Stream Goat for the browser (iPhone, Android, computers): a static web app on GitHub Pages that uses
// Stremio add-ons directly. No server: add-ons, My list, history and progress are saved in this browser.
// CloudStream extensions can't run in a browser; they need the Linux/Android apps (or the server web app).
"use strict";

const $view = document.getElementById("view");
const $tabs = document.getElementById("tabs");
const DEFAULT_ADDONS = [
  "https://v3-cinemeta.strem.io/manifest.json", // catalogs and details (IMDb), no streams
  "https://opensubtitles-v3.strem.io/manifest.json", // subtitles
];
const cache = { meta: {}, home: null, live: null };

// ---------- helpers ----------

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const qs = (o) => Object.entries(o).filter(([, v]) => v != null && v !== "").map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("&");
const icons = {
  play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
  back: '<svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>',
  add: '<svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>',
  list: '<svg viewBox="0 0 24 24"><path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/></svg>',
  subs: '<svg viewBox="0 0 24 24"><path d="M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z"/></svg>',
  next: '<svg viewBox="0 0 24 24"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>',
  trash: '<svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
};
const clock = (s) => { s = Math.floor(s || 0); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); return h ? `${h}h ${m}m` : `${m}m`; };

let toastTimer;
function toast(text) {
  const t = document.getElementById("toast");
  t.textContent = text; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), 3500);
}

function sheet(title, html, onReady) {
  const back = document.createElement("div");
  back.className = "sheet-back";
  back.innerHTML = `<div class="sheet"><div class="grip"></div>${title ? `<h3>${esc(title)}</h3>` : ""}${html}</div>`;
  back.addEventListener("click", (e) => { if (e.target === back) back.remove(); });
  document.body.appendChild(back);
  onReady && onReady(back.querySelector(".sheet"), () => back.remove());
}

// ---------- storage (this browser only) ----------

const store = {
  get(key, def) { try { const v = localStorage.getItem("cs." + key); return v == null ? def : JSON.parse(v); } catch { return def; } },
  set(key, value) { try { localStorage.setItem("cs." + key, JSON.stringify(value)); } catch { toast("Couldn't save: browser storage is full or blocked"); } },
};

// ---------- Stremio add-ons ----------

function manifestUrl(input) {
  let u = input.trim();
  if (u.startsWith("stremio://")) u = "https://" + u.slice("stremio://".length);
  if (!/^https?:\/\//.test(u)) u = "https://" + u;
  u = u.replace(/\/configure\/?$/, "");
  if (!/manifest\.json(\?.*)?$/.test(u)) u = u.replace(/\/?$/, "/manifest.json");
  return u;
}
const baseOf = (url) => url.replace(/\/manifest\.json(\?.*)?$/, "");

function addons() { return store.get("addons", []); }

async function fetchJson(url, ms = 20000) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, { signal: ctl.signal, referrerPolicy: "no-referrer" });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return await r.json();
  } catch (e) {
    if (e.name === "AbortError") throw new Error("The add-on took too long to answer");
    if (e instanceof TypeError) throw new Error("The add-on couldn't be reached (offline, or it doesn't allow browser apps)");
    throw e;
  } finally { clearTimeout(timer); }
}

class NeedsConfig extends Error {
  constructor(m, url) { super(`${m.name} needs setting up first`); this.manifest = m; this.configure = baseOf(url) + "/configure"; }
}

async function installAddon(input, force) {
  const url = manifestUrl(input);
  const m = await fetchJson(url);
  if (!m || !m.id || !m.resources) throw new Error("That isn't a Stremio add-on manifest");
  // The plain link of a configurable add-on (debrid services, etc.) has no account in it: it returns nothing.
  if (!force && m.behaviorHints && m.behaviorHints.configurationRequired) throw new NeedsConfig(m, url);
  const list = addons().filter((a) => a.manifest.id !== m.id);
  list.push({ url, manifest: m });
  store.set("addons", list);
  cache.home = null; cache.live = null;
  return m;
}

async function ensureDefaults() {
  if (store.get("defaultsDone", false)) return;
  for (const u of DEFAULT_ADDONS) { try { await installAddon(u); } catch { /* offline: try next time */ return; } }
  store.set("defaultsDone", true);
}

function supports(a, resource, type, id) {
  const m = a.manifest;
  const r = (m.resources || []).find((x) => (typeof x === "string" ? x : x.name) === resource);
  if (!r) return false;
  const types = (typeof r === "object" && r.types) || m.types;
  const prefixes = (typeof r === "object" && r.idPrefixes) || m.idPrefixes;
  return (!types || !type || types.includes(type)) && (!prefixes || !prefixes.length || !id || prefixes.some((p) => id.startsWith(p)));
}

const res = (a, resource, type, id, extra) =>
  fetchJson(`${baseOf(a.url)}/${resource}/${encodeURIComponent(type)}/${encodeURIComponent(id)}${extra ? "/" + extra : ""}.json`);

const isLiveType = (t) => t === "tv" || t === "channel" || t === "events";

/** Home catalogs: those that need no input (no required search or genre). */
function homeCatalogs(live) {
  return addons().flatMap((a) => (a.manifest.catalogs || [])
    .filter((c) => !(c.extra || []).some((e) => e.isRequired) && !(c.extraRequired || []).length && isLiveType(c.type) === live)
    .map((c) => ({ addon: a, catalog: c })));
}

function item(meta, addon) {
  return { type: meta.type, id: meta.id, name: meta.name, poster: meta.poster, shape: meta.posterShape, addon: addon ? addon.manifest.id : "" };
}

function poster(it, wide) {
  const href = isLiveType(it.type) ? `#/watch?${qs({ type: it.type, id: it.id, v: it.id, name: it.name })}` : `#/title?${qs({ type: it.type, id: it.id, a: it.addon })}`;
  const img = it.poster ? `<img loading="lazy" src="${esc(it.poster)}" alt="" referrerpolicy="no-referrer" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'noimg',textContent:${esc(JSON.stringify(it.name || ""))}}))">`
    : `<div class="noimg">${esc(it.name)}</div>`;
  const progress = it.progress > 0 ? `<div class="bar-progress"><i style="width:${Math.round(it.progress * 100)}%"></i></div>` : "";
  return `<a class="card ${wide ? "wide" : ""}" href="${href}"><div class="pic">${img}${progress}</div><div class="name">${esc(it.name)}</div>${it.sub ? `<div class="sub">${esc(it.sub)}</div>` : ""}</a>`;
}
const shelf = (title, items, wide) => items.length ? `<section class="row"><h2>${esc(title)}</h2><div class="shelf">${items.map((i) => poster(i, wide)).join("")}</div></section>` : "";

const typeName = (t) => ({ movie: "Movies", series: "Series", anime: "Anime", tv: "TV", channel: "Channels" }[t] || t);

// ---------- library (this browser) ----------

const lib = {
  history: () => store.get("history", {}),
  progress: (videoId) => store.get("progress", {})[videoId],
  list: () => store.get("list", []),
  inList: (id) => lib.list().some((x) => x.id === id),
  toggleList(meta) {
    let l = lib.list();
    const on = !l.some((x) => x.id === meta.id);
    l = on ? [{ type: meta.type, id: meta.id, name: meta.name, poster: meta.poster, ts: Date.now() }, ...l] : l.filter((x) => x.id !== meta.id);
    store.set("list", l);
    return on;
  },
  record(meta, video, label, pos, dur) {
    const p = store.get("progress", {});
    p[video] = { pos, dur, ts: Date.now() };
    store.set("progress", p);
    const h = lib.history();
    h[meta.id] = { type: meta.type, id: meta.id, name: meta.name, poster: meta.poster, video, label, pos, dur, ts: Date.now() };
    store.set("history", h);
  },
  continueWatching: () => Object.values(lib.history()).filter((e) => e.dur > 0 && e.pos / e.dur > 0.02 && e.pos / e.dur < 0.92).sort((a, b) => b.ts - a.ts),
};

// ---------- router ----------

const routes = { home, search, title, watch, live, library, more };
async function router() {
  const [path, query] = location.hash.replace(/^#\/?/, "").split("?");
  const name = routes[path] ? path : "home";
  const params = Object.fromEntries(new URLSearchParams(query || ""));
  $tabs.hidden = name === "watch";
  document.body.classList.toggle("no-tabs", name === "watch");
  $tabs.querySelectorAll("a").forEach((a) => a.classList.toggle("on", a.dataset.tab === name || (name === "title" && a.dataset.tab === "home")));
  document.getElementById("tab-live").style.display = homeCatalogs(true).length ? "" : "none";
  window.scrollTo(0, 0);
  try { await routes[name](params); } catch (e) { $view.innerHTML = `<div class="page"><div class="empty"><b>Something went wrong</b>${esc(e.message)}</div></div>`; }
}
window.addEventListener("hashchange", router);

// ---------- home ----------

async function home() {
  $view.innerHTML = `<div class="page"><div class="spinner"></div></div>`;
  await ensureDefaults();
  const catalogs = homeCatalogs(false);
  const cont = lib.continueWatching().map((e) => ({ type: e.type, id: e.id, name: e.name, poster: e.poster, progress: e.pos / e.dur, sub: e.label || `${clock(e.dur - e.pos)} left` }));
  if (!catalogs.length) {
    $view.innerHTML = `<div class="page"><div class="bar"><h1>Home</h1></div>${shelf("Continue watching", cont, true)}
      <div class="empty"><b>No add-ons yet</b>Add Stremio add-ons in the <a href="#/more" style="color:var(--accent)">Add-ons</a> tab.</div></div>`;
    return;
  }
  const rows = cache.home || catalogs.map(({ addon, catalog }) => ({ title: `${catalog.name || typeName(catalog.type)} · ${typeName(catalog.type)}`, addon, catalog, items: null }));
  cache.home = rows;
  const render = () => {
    if (!location.hash.match(/^(#\/?home|#?\/?)$/) && location.hash !== "") return;
    const first = rows.flatMap((r) => r.items || []).find((i) => i.poster);
    $view.innerHTML = `<div class="page">
      ${first ? `<div class="hero"><img src="${esc(first.poster)}" alt="" referrerpolicy="no-referrer"><div class="info"><h1>${esc(first.name)}</h1><div class="actions">
        <a class="btn white" href="#/title?${qs({ type: first.type, id: first.id, a: first.addon })}">${icons.play} Play</a></div></div></div>` : `<div class="bar"><h1>Home</h1></div>`}
      ${shelf("Continue watching", cont, true)}
      ${rows.map((r) => r.items ? shelf(r.title, r.items) : "").join("")}
      ${rows.some((r) => !r.items) ? '<div class="spinner"></div>' : ""}
    </div>`;
  };
  render();
  await Promise.all(rows.filter((r) => !r.items).map(async (r) => {
    try { r.items = ((await res(r.addon, "catalog", r.catalog.type, r.catalog.id)).metas || []).slice(0, 40).map((m) => item(m, r.addon)); }
    catch { r.items = []; }
    render();
  }));
}

// ---------- search ----------

async function search(params) {
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Search</h1></div>
    <form><input class="field" type="search" name="q" placeholder="Movies, series, anime…" value="${esc(params.q || "")}" enterkeyhint="search" autocomplete="off"></form>
    <div id="results"></div></div>`;
  const form = $view.querySelector("form");
  const results = $view.querySelector("#results");
  form.addEventListener("submit", (e) => { e.preventDefault(); form.q.blur(); location.hash = `#/search?${qs({ q: form.q.value.trim() })}`; });
  if (!params.q) { form.q.focus(); results.innerHTML = `<div class="empty">Searches every add-on that can search.</div>`; return; }
  const targets = addons().flatMap((a) => (a.manifest.catalogs || []).filter((c) => (c.extra || []).some((e) => e.name === "search") || (c.extraSupported || []).includes("search")).map((c) => ({ a, c })));
  if (!targets.length) { results.innerHTML = `<div class="empty"><b>No add-on can search</b>Add one in the Add-ons tab.</div>`; return; }
  let pending = targets.length, found = 0;
  results.innerHTML = `<p class="muted small" id="status">Searching…</p><div id="rows"></div>`;
  const rows = results.querySelector("#rows");
  await Promise.all(targets.map(async ({ a, c }) => {
    try {
      const metas = (await res(a, "catalog", c.type, c.id, `search=${encodeURIComponent(params.q)}`)).metas || [];
      if (metas.length) { found += metas.length; rows.insertAdjacentHTML("beforeend", shelf(`${a.manifest.name} · ${c.name || typeName(c.type)}`, metas.slice(0, 40).map((m) => item(m, a)))); }
    } catch { /* this add-on failed; the others still show */ }
    pending--;
    const st = results.querySelector("#status");
    if (st) st.textContent = pending ? `Searching… ${pending} left` : found ? "" : `Nothing found for “${params.q}”.`;
  }));
}

// ---------- title page ----------

async function loadMeta(type, id, addonId) {
  const key = type + "\n" + id;
  if (cache.meta[key]) return cache.meta[key];
  const all = addons().filter((a) => supports(a, "meta", type, id));
  all.sort((x, y) => (y.manifest.id === addonId) - (x.manifest.id === addonId));
  for (const a of all) {
    try { const m = (await res(a, "meta", type, id)).meta; if (m) { cache.meta[key] = m; return m; } } catch { /* next */ }
  }
  throw new Error("None of your add-ons has details for this title");
}

function videosOf(meta) {
  const v = (meta.videos || []).slice();
  if (!v.length) return [{ id: (meta.behaviorHints && meta.behaviorHints.defaultVideoId) || meta.id, title: meta.name, movie: true }];
  v.sort((a, b) => ((a.season || 0) === 0) - ((b.season || 0) === 0) || (a.season || 0) - (b.season || 0) || (a.episode || 0) - (b.episode || 0));
  return v;
}
const label = (v) => v.movie ? "" : [v.season != null ? `S${v.season}` : "", v.episode != null ? `E${v.episode}` : ""].join("") + (v.title || v.name ? ` · ${v.title || v.name}` : "");

async function title(params) {
  $view.innerHTML = `<div class="page"><div class="spinner"></div></div>`;
  const meta = await loadMeta(params.type, params.id, params.a);
  const videos = videosOf(meta);
  const hist = lib.history()[meta.id];
  const target = (hist && videos.find((v) => v.id === hist.video)) || videos.find((v) => (v.season || 0) > 0) || videos[0];
  const tp = lib.progress(target.id);
  const resume = tp && tp.dur && tp.pos / tp.dur < 0.92 && tp.pos > 30;
  const playText = (resume ? "Resume" : "Play") + (target.movie ? "" : ` ${label(target).split(" · ")[0]}`) + (resume ? ` · ${clock(tp.pos)}` : "");
  const watchHref = (v) => `#/watch?${qs({ type: meta.type, id: meta.id, v: v.id, a: params.a })}`;
  const seasons = [...new Set(videos.filter((v) => !v.movie).map((v) => v.season ?? 0))];
  let season = target.season ?? seasons[0];
  const rating = parseFloat(meta.imdbRating);
  $view.innerHTML = `<div class="page">
    <div class="title-hero">${meta.background || meta.poster ? `<img src="${esc(meta.background || meta.poster)}" alt="" referrerpolicy="no-referrer">` : ""}</div>
    <button class="icon-btn back" onclick="history.length > 1 ? history.back() : (location.hash='#/home')">${icons.back}</button>
    <div class="title-info">${meta.logo ? `<img src="${esc(meta.logo)}" alt="${esc(meta.name)}" referrerpolicy="no-referrer" style="max-width:70%;max-height:90px;margin-bottom:10px" onerror="this.outerHTML='<h1>${esc(meta.name).replace(/'/g, "")}</h1>'">` : `<h1>${esc(meta.name)}</h1>`}
      <div class="meta">${rating ? `<span class="tag accent">IMDb ${rating.toFixed(1)}</span>` : ""}${meta.releaseInfo ? `<span>${esc(meta.releaseInfo)}</span>` : ""}
        ${meta.certification ? `<span class="tag">${esc(meta.certification)}</span>` : ""}${meta.runtime ? `<span>${esc(meta.runtime)}</span>` : ""}
        ${seasons.length ? `<span>${videos.filter((v) => !v.movie).length} episodes</span>` : ""}</div>
      <div class="title-actions">
        <a class="btn white wide" href="${watchHref(target)}">${icons.play} ${esc(playText)}</a>
        <div class="row2">
          <button class="btn ${lib.inList(meta.id) ? "on" : ""}" id="bm">${lib.inList(meta.id) ? icons.check : icons.add}<span>My list</span></button>
          <button class="btn" id="seen">${icons.check}<span>Watched</span></button>
          <a class="btn" href="#/search?${qs({ q: meta.name })}">${icons.list}<span>Search all</span></a>
        </div>
      </div>
      ${meta.description ? `<p class="plot clamped">${esc(meta.description)}</p>` : ""}
      ${(meta.genres || meta.genre || []).length ? `<div class="meta">${(meta.genres || meta.genre).slice(0, 6).map((g) => `<span class="tag">${esc(g)}</span>`).join("")}</div>` : ""}
      ${(meta.cast || []).length ? `<p class="muted small">Cast: ${esc(meta.cast.slice(0, 8).join(", "))}</p>` : ""}
      ${seasons.length ? `<h2>Episodes</h2><div id="episodes"></div>` : ""}
    </div></div>`;
  const renderEpisodes = () => {
    const box = $view.querySelector("#episodes");
    if (!box) return;
    box.innerHTML = (seasons.length > 1 ? `<div class="chips">${seasons.map((s) => `<button class="chip ${s === season ? "on" : ""}" data-s="${s}">${s === 0 ? "Specials" : `Season ${s}`}</button>`).join("")}</div>` : "") +
      videos.filter((v) => (v.season ?? 0) === season).map((v) => {
        const p = lib.progress(v.id);
        const frac = p && p.dur ? p.pos / p.dur : 0;
        return `<a class="ep" href="${watchHref(v)}"><div class="thumb">${v.thumbnail ? `<img loading="lazy" src="${esc(v.thumbnail)}" alt="" referrerpolicy="no-referrer">` : `<div class="noimg">E${esc(v.episode ?? "")}</div>`}
          ${frac > 0.02 ? `<div class="bar-progress"><i style="width:${Math.round(Math.min(frac, 1) * 100)}%"></i></div>` : ""}</div>
          <div class="t"><div class="code">${v.episode != null ? `E${v.episode}` : ""}</div><div class="n">${esc(v.title || v.name || `Episode ${v.episode ?? ""}`)}</div>
          ${frac > 0.92 ? '<div class="done">✓ Watched</div>' : v.overview || v.description ? `<div class="d">${esc(v.overview || v.description)}</div>` : ""}</div></a>`;
      }).join("");
    box.querySelectorAll("[data-s]").forEach((b) => b.addEventListener("click", () => { season = +b.dataset.s; renderEpisodes(); }));
  };
  renderEpisodes();
  const plot = $view.querySelector(".plot");
  plot && plot.addEventListener("click", () => plot.classList.toggle("clamped"));
  $view.querySelector("#bm").addEventListener("click", (e) => {
    const on = lib.toggleList(meta);
    e.currentTarget.classList.toggle("on", on);
    e.currentTarget.innerHTML = `${on ? icons.check : icons.add}<span>My list</span>`;
    toast(on ? "Added to My list" : "Removed from My list");
  });
  $view.querySelector("#seen").addEventListener("click", () => {
    const p = store.get("progress", {});
    videos.forEach((v) => { p[v.id] = { pos: 1, dur: 1, ts: Date.now() }; });
    store.set("progress", p);
    const h = lib.history(); delete h[meta.id]; store.set("history", h);
    toast("Marked as watched"); title(params);
  });
}

// ---------- streams ----------

/** What the browser can play, and why not when it can't. */
function classify(s) {
  if (s.url) {
    const headers = Object.keys((s.behaviorHints && s.behaviorHints.proxyHeaders && s.behaviorHints.proxyHeaders.request) || {}).filter((k) => k.toLowerCase() !== "user-agent");
    if (location.protocol === "https:" && s.url.startsWith("http:")) return { ok: false, why: "Not a secure (https) link: browsers block it" };
    if (headers.length) return { ok: false, why: "Needs special headers: works in the Stream Goat apps, not in a browser" };
    const mkv = /\.mkv(\?|$)/i.test(s.url);
    return { ok: true, why: mkv ? "MKV: may not play on iPhone" : s.behaviorHints && s.behaviorHints.notWebReady ? "May not play in a browser" : "" };
  }
  if (s.infoHash) return { ok: false, why: "Torrent: not supported" };
  if (s.ytId) return { ok: false, external: `https://www.youtube.com/watch?v=${s.ytId}`, why: "Opens YouTube" };
  if (s.externalUrl) return { ok: false, external: s.externalUrl, why: "Opens a website" };
  return { ok: false, why: "Unknown stream" };
}

async function streamsFor(type, videoId) {
  const all = addons().filter((a) => supports(a, "stream", type, videoId));
  const lists = await Promise.all(all.map(async (a) => {
    try { return ((await res(a, "stream", type, videoId)).streams || []).map((s) => ({ ...s, addon: a.manifest.name, c: classify(s) })); }
    catch { return []; }
  }));
  return lists.flat().sort((x, y) => (y.c.ok - x.c.ok) || (!!x.c.why - !!y.c.why));
}

async function subtitlesFor(type, videoId) {
  const all = addons().filter((a) => supports(a, "subtitles", type, videoId));
  const lists = await Promise.all(all.map(async (a) => { try { return (await res(a, "subtitles", type, videoId)).subtitles || []; } catch { return []; } }));
  return lists.flat().filter((s) => s.url && s.url.startsWith("https:") || location.protocol === "http:");
}

let langNames;
function langName(code) {
  try { langNames = langNames || new Intl.DisplayNames(["en"], { type: "language" }); return langNames.of(code) || code; } catch { return code; }
}

function toVtt(text) {
  const t = text.replace(/^﻿/, "").replace(/\r\n?/g, "\n");
  return t.trimStart().startsWith("WEBVTT") ? t : "WEBVTT\n\n" + t.replace(/(\d{1,2}:\d{2}:\d{2}),(\d{3})/g, "$1.$2");
}

let hlsLoading;
function loadHls() {
  hlsLoading = hlsLoading || new Promise((ok, fail) => {
    const s = document.createElement("script");
    s.src = "https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js";
    s.onload = () => ok(window.Hls); s.onerror = () => fail(new Error("Couldn't load the HLS player"));
    document.head.appendChild(s);
  });
  return hlsLoading;
}

// ---------- player ----------

async function watch(params) {
  const live = isLiveType(params.type);
  document.body.style.overflow = "hidden";
  $view.innerHTML = `<div class="player">
    <div class="top"><button class="icon-btn" id="close">${icons.back}</button><div class="t"><b id="ptitle">${esc(params.name || "")}</b><span class="muted small" id="psource">Finding streams…</span></div></div>
    <video playsinline controls autoplay preload="auto"></video><div class="status" id="pstatus"><div class="spinner"></div>Finding streams…</div>
    <div class="bottom"><button class="btn small" id="srcs">${icons.list} Streams</button><button class="btn small" id="subs">${icons.subs} Subtitles</button>
      <button class="btn small" id="nextBtn" hidden>${icons.next} Next episode</button></div></div>`;
  const video = $view.querySelector("video");
  const status = $view.querySelector("#pstatus");
  const setStatus = (html) => { status.innerHTML = html; status.hidden = !html; };
  let saveTimer, countdown, hls;
  const leave = () => { saveProgress(); clearInterval(saveTimer); clearInterval(countdown); hls && hls.destroy(); document.body.style.overflow = ""; };
  $view.querySelector("#close").addEventListener("click", () => { history.length > 1 ? history.back() : (location.hash = "#/home"); });
  window.addEventListener("hashchange", function off() { leave(); window.removeEventListener("hashchange", off); });
  window.addEventListener("pagehide", () => saveProgress());

  let meta = null, videos = [], v = null, next = null;
  if (!live) {
    meta = await loadMeta(params.type, params.id, params.a);
    videos = videosOf(meta);
    const i = videos.findIndex((x) => x.id === params.v);
    v = videos[i] || videos[0];
    next = i >= 0 && !v.movie ? videos.slice(i + 1).find((x) => (x.season ?? 0) > 0 || (v.season ?? 0) === 0) : null;
    $view.querySelector("#ptitle").textContent = v.movie ? meta.name : `${meta.name} · ${label(v)}`;
    if (next) { const nb = $view.querySelector("#nextBtn"); nb.hidden = false; nb.addEventListener("click", () => goNext()); }
  }
  const videoId = live ? params.id : v.id;
  const [streams, subs] = await Promise.all([streamsFor(params.type, videoId), live ? [] : subtitlesFor(params.type, videoId)]);
  const playable = streams.filter((s) => s.c.ok);
  if (!streams.length) return setStatus(`<b>No streams</b><br>None of your add-ons has a stream for this. Add a stream add-on in the Add-ons tab.`);

  const p0 = !live && lib.progress(videoId);
  let resumeAt = p0 && p0.dur && p0.pos / p0.dur < 0.92 && p0.pos > 10 ? p0.pos : 0;
  let current = null;
  const play = async (s) => {
    current = s;
    $view.querySelector("#psource").textContent = [s.addon, (s.name || "").replace(/\n/g, " "), s.c.why].filter(Boolean).join(" · ");
    setStatus('<div class="spinner"></div>Loading…');
    if (video.currentTime > 5) resumeAt = video.currentTime;
    hls && hls.destroy(); hls = null;
    const isHls = /\.m3u8(\?|$)/i.test(s.url);
    if (isHls && !video.canPlayType("application/vnd.apple.mpegurl")) {
      try {
        const Hls = await loadHls();
        hls = new Hls();
        hls.on(Hls.Events.ERROR, (_, d) => { if (d.fatal) failed(); });
        hls.loadSource(s.url); hls.attachMedia(video);
      } catch (e) { return setStatus(esc(e.message)); }
    } else { video.src = s.url; video.load(); }
    video.play().catch(() => setStatus("Tap ▶ to start"));
  };
  // A stream that fails moves on to the next playable one, keeping the position (never to the next episode).
  const failed = () => {
    const rest = playable.slice(playable.indexOf(current) + 1);
    if (rest.length) { toast("That stream failed, trying the next one"); play(rest[0]); }
    else setStatus("<b>None of the streams would play.</b><br>Pick one in Streams, or try another add-on.");
  };
  video.addEventListener("loadedmetadata", () => { if (resumeAt > 5 && isFinite(video.duration) && resumeAt < video.duration - 30) video.currentTime = resumeAt; });
  video.addEventListener("playing", () => setStatus(""));
  video.addEventListener("waiting", () => setStatus('<div class="spinner"></div>'));
  video.addEventListener("error", () => { if (!hls) failed(); });
  video.addEventListener("timeupdate", () => {
    if (!next || !isFinite(video.duration) || video.duration < 300 || countdown) return;
    if (video.currentTime > video.duration - 20) showNext();
  });
  video.addEventListener("ended", () => { saveProgress(true); if (next && video.duration >= 180) showNext(); });

  function showNext() {
    if (countdown || !next) return;
    let n = 10;
    const card = document.createElement("div");
    card.className = "next-card";
    card.innerHTML = `<div class="label">NEXT EPISODE</div><b>${esc(label(next))}</b><div class="muted small" id="cd">Starts in ${n} s</div>
      <div class="actions"><button class="btn primary small" id="now">${icons.play} Play now</button><button class="btn small" id="cancel">Cancel</button></div>`;
    $view.querySelector(".player").appendChild(card);
    card.querySelector("#now").addEventListener("click", goNext);
    card.querySelector("#cancel").addEventListener("click", () => { clearInterval(countdown); card.remove(); countdown = "cancelled"; });
    countdown = setInterval(() => { n--; const cd = card.querySelector("#cd"); if (cd) cd.textContent = `Starts in ${n} s`; if (n <= 0) goNext(); }, 1000);
  }
  function goNext() { clearInterval(countdown); saveProgress(); location.replace(`#/watch?${qs({ type: params.type, id: params.id, v: next.id, a: params.a })}`); }
  function saveProgress(finished) {
    if (live || !meta || !isFinite(video.duration) || video.duration <= 0 || video.currentTime < 5) return;
    lib.record(meta, videoId, v.movie ? "" : label(v).split(" · ")[0], finished ? video.duration : video.currentTime, video.duration);
  }
  saveTimer = setInterval(() => { if (!video.paused) saveProgress(); }, 10000);
  video.addEventListener("pause", () => saveProgress());

  $view.querySelector("#srcs").addEventListener("click", () => sheet("Streams", streams.map((s, i) =>
    `<button class="option ${s === current ? "on" : ""}" data-i="${i}" ${s.c.ok || s.c.external ? "" : "disabled"}><span>${esc(s.addon)} · ${esc((s.name || "").replace(/\n/g, " "))}
      <span class="why">${esc([(s.title || s.description || "").replace(/\n/g, " ").slice(0, 120), s.c.why].filter(Boolean).join(" · "))}</span></span></button>`).join(""), (el, close) => {
    el.querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", () => {
      const s = streams[+b.dataset.i]; close();
      if (s.c.external) window.open(s.c.external, "_blank", "noopener"); else play(s);
    }));
  }));
  $view.querySelector("#subs").addEventListener("click", () => {
    if (!subs.length) return toast("No subtitles found for this");
    sheet("Subtitles", `<button class="option" data-i="-1">Off</button>` + subs.slice(0, 60).map((s, i) =>
      `<button class="option" data-i="${i}">${esc(langName(s.lang || "und"))}</button>`).join(""), (el, close) => {
      el.querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", async () => {
        close();
        [...video.textTracks].forEach((t) => (t.mode = "disabled"));
        const i = +b.dataset.i;
        if (i < 0) return;
        try {
          const r = await fetch(subs[i].url, { referrerPolicy: "no-referrer" });
          const url = URL.createObjectURL(new Blob([toVtt(await r.text())], { type: "text/vtt" }));
          const track = document.createElement("track");
          Object.assign(track, { kind: "subtitles", label: langName(subs[i].lang || "und"), srclang: (subs[i].lang || "en").slice(0, 2), src: url, default: true });
          video.appendChild(track);
          track.track.mode = "showing";
        } catch { toast("Couldn't load those subtitles"); }
      }));
    });
  });

  if (!playable.length) {
    setStatus(`<b>No stream this browser can play</b><br>${esc(streams[0].c.why)}. Open Streams to see them all.`);
    $view.querySelector("#psource").textContent = `${streams.length} stream(s), none playable here`;
    return;
  }
  play(playable[0]);
}

// ---------- Live TV (Stremio TV add-ons) ----------

async function live() {
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Live TV</h1></div><div class="spinner"></div></div>`;
  const cats = homeCatalogs(true);
  if (!cats.length) { $view.querySelector(".spinner").outerHTML = `<div class="empty"><b>No TV add-ons</b>Add a Stremio add-on with TV channels in the Add-ons tab.</div>`; return; }
  cache.live = cache.live || await Promise.all(cats.map(async ({ addon, catalog }) => {
    try { return { title: `${catalog.name || "Channels"} · ${addon.manifest.name}`, items: ((await res(addon, "catalog", catalog.type, catalog.id)).metas || []).map((m) => item(m, addon)) }; }
    catch { return { title: catalog.name, items: [] }; }
  }));
  $view.querySelector(".page").innerHTML = `<div class="bar"><h1>Live TV</h1></div>${cache.live.map((r) => r.items.length ? `<h2>${esc(r.title)}</h2><div class="grid">${r.items.slice(0, 200).map((i) => poster(i)).join("")}</div>` : "").join("") || '<div class="empty">The TV add-ons returned no channels.</div>'}`;
}

// ---------- library ----------

async function library(params) {
  const tab = params.tab || "continue";
  const tabs = { continue: "Continue watching", list: "My list", history: "History" };
  let items;
  if (tab === "list") items = lib.list();
  else items = (tab === "history" ? Object.values(lib.history()).sort((a, b) => b.ts - a.ts) : lib.continueWatching())
    .map((e) => ({ ...e, progress: e.dur ? e.pos / e.dur : 0, sub: [e.label, e.dur && e.pos / e.dur > 0.92 ? "Watched" : `${clock(e.dur - e.pos)} left`].filter(Boolean).join(" · ") }));
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Library</h1></div>
    <div class="chips">${Object.entries(tabs).map(([k, v]) => `<a class="chip ${k === tab ? "on" : ""}" href="#/library?tab=${k}">${esc(v)}</a>`).join("")}</div>
    <div style="margin-top:14px">${items.length ? `<div class="grid">${items.map((i) => poster(i)).join("")}</div>` : `<div class="empty"><b>Nothing yet</b>${tab === "list" ? "Use “My list” on a title to keep it here." : "What you watch shows up here."}</div>`}</div>
    <p class="faint small center" style="margin-top:24px">Saved in this browser on this device.</p></div>`;
}

// ---------- add-ons, backup, about ----------

async function more() {
  const list = addons();
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Add-ons</h1></div>
    <form id="add"><input class="field" name="url" placeholder="Add-on link (…/manifest.json or stremio://…)" autocomplete="off" autocapitalize="off" spellcheck="false">
      <button class="btn primary wide" style="margin-top:10px">${icons.add} Install</button></form>
    <div class="note">Paste the <b>manifest link</b> of a Stremio add-on (the same ones Stremio and Nuvio use; the add-on's
      website shows it after you configure it). Add-ons that give <b>direct https video links</b> play best here.
      Torrent add-ons and links that need special headers only work in the Stream Goat apps.
      <b>Debrid add-ons</b> work when set up with your account, but on iPhone MKV files often won't play: pick MP4 results.</div>
    <div id="addons">${list.map((a, i) => `<div class="addon">${a.manifest.logo ? `<img src="${esc(a.manifest.logo)}" alt="" referrerpolicy="no-referrer">` : `<div class="noimg">${esc(a.manifest.name.slice(0, 2))}</div>`}
      <div class="t"><b>${esc(a.manifest.name)}</b><span>${esc([(a.manifest.resources || []).map((r) => typeof r === "string" ? r : r.name).join(", "), a.manifest.description].filter(Boolean).join(" · "))}</span></div>
      <button class="icon-btn" data-rm="${i}" aria-label="Remove">${icons.trash}</button></div>`).join("") || '<div class="empty">No add-ons installed.</div>'}</div>
    <h2>Backup</h2>
    <p class="muted small">Your add-ons, My list, history and progress are saved in this browser only. Back them up to move them to another phone or browser.</p>
    <div style="display:flex;gap:10px"><button class="btn small" id="exp">Back up</button><label class="btn small">Restore<input type="file" accept="application/json,.json" id="imp" hidden></label></div>
    <h2>On iPhone</h2>
    <p class="muted small">In Safari tap <b>Share</b> → <b>Add to Home Screen</b>. Stream Goat then opens full screen like an app, with its own storage.</p>
    <h2>About</h2>
    <p class="muted small"><b style="color:var(--accent)">Created by Syed Ali Hamza.</b> Stream Goat web, the free browser version of <a href="https://github.com/sherazi5110/cloudstream-Linxdroid" style="color:var(--accent)">Stream Goat for Linux, Windows and Android</a>.
      It plays streams from Stremio add-ons; CloudStream extensions need the Linux, Windows or Android app. This page hosts no content.</p>
  </div>`;
  const form = $view.querySelector("#add");
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const btn = form.querySelector("button"); btn.disabled = true;
    try { const m = await installAddon(form.url.value); toast(`Installed ${m.name}`); more(); }
    catch (err) {
      btn.disabled = false;
      if (!(err instanceof NeedsConfig)) return toast(err.message);
      sheet(`Set up ${err.manifest.name}`, `<p class="muted">This add-on needs your settings (for example your debrid account key) before it can find streams.</p>
        <ol class="muted small" style="padding-left:18px;line-height:1.6"><li>Tap <b>Open setup page</b> and fill it in.</li>
        <li>At the end, copy the <b>install link</b> it gives you (often “Copy link” or “Install”; it starts with <b>stremio://</b> or ends with <b>manifest.json</b>).</li>
        <li>Come back here and paste that link into the box, then tap Install.</li></ol>
        <a class="btn primary wide" href="${esc(err.configure)}" target="_blank" rel="noopener">Open setup page</a>
        <button class="btn wide" style="margin-top:10px" id="anyway">Install without setup</button>`, (el, close) => {
        el.querySelector("#anyway").addEventListener("click", async () => {
          close();
          try { const m = await installAddon(form.url.value, true); toast(`Installed ${m.name} (not set up)`); more(); } catch (e2) { toast(e2.message); }
        });
      });
    }
  });
  $view.querySelectorAll("[data-rm]").forEach((b) => b.addEventListener("click", () => {
    const l = addons(); const [gone] = l.splice(+b.dataset.rm, 1);
    if (!confirm(`Remove ${gone.manifest.name}?`)) return;
    store.set("addons", l); store.set("defaultsDone", true); cache.home = null; cache.live = null; more();
  }));
  $view.querySelector("#exp").addEventListener("click", () => {
    const data = { format: "cloudstream-web-backup", version: 1, addons: addons().map((a) => a.url), list: lib.list(), history: lib.history(), progress: store.get("progress", {}) };
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([JSON.stringify(data)], { type: "application/json" }));
    a.download = `streamgoat-web-backup-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
  });
  $view.querySelector("#imp").addEventListener("change", async (e) => {
    try {
      const data = JSON.parse(await e.target.files[0].text());
      if (data.format !== "cloudstream-web-backup") throw new Error("That isn't a Stream Goat web backup");
      store.set("list", data.list || []); store.set("history", data.history || {}); store.set("progress", data.progress || {});
      store.set("addons", []); store.set("defaultsDone", true);
      for (const u of data.addons || []) { try { await installAddon(u); } catch { /* skip unreachable */ } }
      toast("Restored"); more();
    } catch (err) { toast(err.message); }
  });
}

router();
