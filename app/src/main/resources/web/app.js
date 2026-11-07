// CloudStream web app: the phone front end of `cloudstream-linux serve`. No build step, no libraries.
"use strict";

const $view = document.getElementById("view");
const $tabs = document.getElementById("tabs");
const state = { me: null, providers: null, home: {}, titles: {}, live: null };

// ---------- helpers ----------

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const qs = (o) => Object.entries(o).filter(([, v]) => v != null).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("&");
const icons = {
  play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
  back: '<svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>',
  expand: '<svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5z"/></svg>',
  add: '<svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>',
  list: '<svg viewBox="0 0 24 24"><path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/></svg>',
  subs: '<svg viewBox="0 0 24 24"><path d="M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z"/></svg>',
  next: '<svg viewBox="0 0 24 24"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>',
  search: '<svg viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>',
  star: '<svg viewBox="0 0 24 24"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 5.73L5.82 21z"/></svg>',
};
const avatarColors = { blue: "#3b82f6", red: "#ef4444", green: "#10b981", purple: "#8b5cf6", orange: "#f97316", pink: "#ec4899", teal: "#14b8a6", yellow: "#eab308" };
function avatar(p, cls = "") {
  const color = avatarColors[(p.avatar || "").split(":")[1]] || "#3b82f6";
  return `<div class="avatar ${cls}" style="background:${color}">${esc((p.name || "?").trim().charAt(0).toUpperCase())}</div>`;
}
const clock = (s) => { s = Math.floor(s || 0); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); return h ? `${h}h ${m}m` : `${m}m`; };
const hhmm = (ms) => new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

let toastTimer;
function toast(text) {
  const t = document.getElementById("toast");
  t.textContent = text; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), 3500);
}

class LoginNeeded extends Error {}
async function api(path, body) {
  const r = await fetch(path, body === undefined ? { credentials: "same-origin" } : {
    method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  if (r.status === 401) { location.hash = "#/login"; throw new LoginNeeded(); }
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || `Error ${r.status}`);
  return data;
}

function sheet(title, html, onReady) {
  const back = document.createElement("div");
  back.className = "sheet-back";
  back.innerHTML = `<div class="sheet"><div class="grip"></div>${title ? `<h3>${esc(title)}</h3>` : ""}${html}</div>`;
  back.addEventListener("click", (e) => { if (e.target === back) back.remove(); });
  document.body.appendChild(back);
  onReady && onReady(back.querySelector(".sheet"), () => back.remove());
  return () => back.remove();
}

function poster(item, wide) {
  const img = item.poster ? `<img loading="lazy" src="${esc(item.poster)}" alt="" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'noimg',textContent:${esc(JSON.stringify(item.name))}}))">`
    : `<div class="noimg">${esc(item.name)}</div>`;
  const progress = item.progress > 0 ? `<div class="bar-progress"><i style="width:${Math.round(item.progress * 100)}%"></i></div>` : "";
  return `<a class="card ${wide ? "wide" : ""}" href="#/title?${qs({ api: item.api, url: item.url })}">
    <div class="pic">${img}${progress}</div><div class="name">${esc(item.name)}</div>${item.sub ? `<div class="sub">${esc(item.sub)}</div>` : ""}</a>`;
}
const shelf = (title, items, wide) => items.length ? `<section class="row"><h2>${esc(title)}</h2><div class="shelf">${items.map((i) => poster(i, wide)).join("")}</div></section>` : "";

// ---------- router ----------

const routes = { login, profiles, home, search, title, watch, live, library, more };
async function router() {
  const [path, query] = location.hash.replace(/^#\/?/, "").split("?");
  const name = path || "home";
  const params = Object.fromEntries(new URLSearchParams(query || ""));
  const view = routes[name] || home;
  const tabbed = !["login", "profiles", "watch"].includes(name);
  $tabs.hidden = !tabbed;
  document.body.classList.toggle("no-tabs", !tabbed);
  $tabs.querySelectorAll("a").forEach((a) => a.classList.toggle("on", a.dataset.tab === name || (name === "title" && a.dataset.tab === "home")));
  if (name !== "login" && !state.me) {
    try { state.me = await api("/api/me"); applyMe(); } catch (e) { if (e instanceof LoginNeeded) return; $view.innerHTML = `<div class="empty"><b>Can't reach the server</b>${esc(e.message)}</div>`; return; }
    if (!sessionStorage.getItem("picked") && state.me.profiles.length > 1 && name !== "profiles") { location.hash = "#/profiles"; return; }
  }
  window.scrollTo(0, 0);
  try { await view(params); } catch (e) { if (!(e instanceof LoginNeeded)) $view.innerHTML = `<div class="page"><div class="empty"><b>Something went wrong</b>${esc(e.message)}</div></div>`; }
}
function applyMe() {
  document.documentElement.style.setProperty("--accent", state.me.accent || "#3b82f6");
  document.getElementById("tab-live").style.display = state.me.live ? "" : "none";
}
window.addEventListener("hashchange", router);

// ---------- login and profiles ----------

async function login() {
  $view.innerHTML = `<form class="login"><img src="icon-192.png" alt=""><h1>CloudStream</h1>
    <input class="field" type="password" name="pw" placeholder="Password" autocomplete="current-password" required>
    <button class="btn primary wide">Log in</button><div class="error"></div>
    <p class="faint small center">Your own private CloudStream server.</p></form>`;
  const form = $view.querySelector("form");
  form.pw.focus();
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    form.querySelector("button").disabled = true;
    try {
      await api("/api/login", { password: form.pw.value });
      state.me = null; sessionStorage.removeItem("picked");
      location.hash = "#/home";
    } catch (err) {
      form.querySelector(".error").textContent = err.message;
      form.querySelector("button").disabled = false;
    }
  });
}

async function profiles() {
  state.me = await api("/api/me"); applyMe();
  $view.innerHTML = `<div class="who"><h1>Who's watching?</h1><div class="who-grid">${state.me.profiles.map((p) =>
    `<button data-id="${esc(p.id)}">${avatar(p)}<span>${esc(p.name)}</span>${p.locked ? '<span class="lock">PIN</span>' : ""}</button>`).join("")}</div></div>`;
  $view.querySelectorAll("[data-id]").forEach((b) => b.addEventListener("click", () => {
    const p = state.me.profiles.find((x) => x.id === b.dataset.id);
    const go = async (pin) => {
      await api("/api/profile", { id: p.id, pin });
      sessionStorage.setItem("picked", "1");
      state.me = null; state.home = {}; state.providers = null; state.live = null;
      location.hash = "#/home";
    };
    if (!p.locked) return go().catch((e) => toast(e.message));
    sheet(`PIN for ${p.name}`, `<input class="field" type="password" inputmode="numeric" maxlength="8" placeholder="PIN"><div class="error"></div><button class="btn primary wide">Open</button>`, (el, close) => {
      const input = el.querySelector("input"); input.focus();
      const tryPin = () => go(input.value).then(close).catch((e) => (el.querySelector(".error").textContent = e.message));
      el.querySelector("button").addEventListener("click", tryPin);
      input.addEventListener("keydown", (e) => e.key === "Enter" && tryPin());
    });
  }));
}

// ---------- home ----------

async function home() {
  state.providers = state.providers || await api("/api/providers");
  const withHome = state.providers.filter((p) => p.home);
  if (!withHome.length) {
    $view.innerHTML = `<div class="page"><div class="bar"><h1>Home</h1></div><div class="empty"><b>No extensions yet</b>Add repositories and install extensions in the CloudStream app on the server (or run <code>cloudstream-linux install</code> there).</div></div>`;
    return;
  }
  let provider = localStorage.getItem("provider");
  if (!withHome.some((p) => p.name === provider)) provider = withHome[0].name;
  const lib = await api("/api/library").catch(() => ({ continue: [] }));
  const render = (data) => {
    const sections = data ? data.sections : [];
    const first = sections.flatMap((s) => s.items).find((i) => i.poster);
    const cont = lib.continue.map((e) => ({ ...e, sub: e.label || `${clock(e.duration - e.position)} left` }));
    $view.innerHTML = `<div class="page">
      ${first ? `<div class="hero"><img src="${esc(first.poster)}" alt=""><div class="info"><h1>${esc(first.name)}</h1><div class="actions">
        <a class="btn white" href="#/title?${qs({ api: first.api, url: first.url })}">${icons.play} Play</a></div></div></div>` : `<div style="height:60px"></div>`}
      <div class="home-top"><button class="provider-pick">${icons.list}<span>${esc(provider)}</span>${icons.expand}</button>
        <a class="icon-btn" href="#/more">${avatar(state.me.profile, "sm")}</a></div>
      ${shelf("Continue watching", cont, true)}
      ${data ? sections.map((s) => shelf(s.name, s.items, s.horizontal)).join("") || `<div class="empty"><b>Nothing here</b>${esc(provider)} returned no catalogs. Try another extension.</div>` : '<div class="spinner"></div>'}
    </div>`;
    $view.querySelector(".provider-pick").addEventListener("click", () => sheet("Extension", withHome.map((p) =>
      `<button class="option ${p.name === provider ? "on" : ""}" data-name="${esc(p.name)}">${esc(p.name)} <span class="muted small">${esc(p.lang.toUpperCase())}</span></button>`).join(""), (el, close) => {
      el.querySelectorAll("[data-name]").forEach((b) => b.addEventListener("click", () => { localStorage.setItem("provider", b.dataset.name); close(); home(); }));
    }));
  };
  render(state.home[provider]);
  if (!state.home[provider]) {
    const data = await api(`/api/home?${qs({ provider })}`);
    state.home[provider] = data;
    if (location.hash.startsWith("#/home") || location.hash === "" || location.hash === "#/") render(data);
  }
}

// ---------- search ----------

async function search(params) {
  state.providers = state.providers || await api("/api/providers");
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Search</h1></div>
    <form><input class="field" type="search" name="q" placeholder="Movies, series, anime…" value="${esc(params.q || "")}" enterkeyhint="search" autocomplete="off"></form>
    <div id="results"></div></div>`;
  const form = $view.querySelector("form");
  const results = $view.querySelector("#results");
  form.addEventListener("submit", (e) => { e.preventDefault(); form.q.blur(); location.hash = `#/search?${qs({ q: form.q.value.trim() })}`; });
  if (!params.q) { form.q.focus(); results.innerHTML = `<div class="empty">Results come from every extension, as each one answers.</div>`; return; }
  const q = params.q;
  const providers = state.providers.map((p) => p.name);
  let pending = providers.length;
  results.innerHTML = `<p class="muted small" id="status">Searching ${pending} extensions…</p><div id="rows"></div>`;
  const rows = results.querySelector("#rows");
  let found = 0;
  // One request per extension, so fast ones show up first.
  await Promise.all(providers.map(async (name) => {
    try {
      const batches = await api(`/api/search?${qs({ q, provider: name })}`);
      if (!location.hash.includes(encodeURIComponent(q)) && !location.hash.includes(q)) return;
      for (const b of batches) { found += b.items.length; rows.insertAdjacentHTML("beforeend", shelf(`${b.provider} · ${b.items.length}`, b.items)); }
    } catch (e) { if (e instanceof LoginNeeded) throw e; }
    pending--;
    const st = results.querySelector("#status");
    if (st) st.textContent = pending ? `Searching… ${pending} extension(s) left` : found ? "" : `Nothing found for “${q}”.`;
  }));
}

// ---------- title page ----------

async function title(params) {
  $view.innerHTML = `<div class="page"><div class="spinner"></div></div>`;
  const key = params.api + "\n" + params.url;
  const t = await api(`/api/load?${qs({ api: params.api, url: params.url })}`);
  state.titles[key] = t;
  const groups = {};
  t.episodes.forEach((e) => {
    const g = [e.dub, e.season != null ? `Season ${e.season}` : ""].filter(Boolean).join(" · ") || "Episodes";
    (groups[g] = groups[g] || []).push(e);
  });
  const groupNames = Object.keys(groups);
  let group = groupNames.find((g) => groups[g].some((e) => t.resume && e.data === t.resume.data)) || groupNames[0];
  const resumeText = !t.resume ? "Play" : (t.resume.position > 30 ? "Resume" : "Play") + (t.series ? ` ${t.resume.label}` : "") + (t.resume.position > 30 ? ` · ${clock(t.resume.position)}` : "");
  const watch = (data) => `#/watch?${qs({ api: t.api, url: t.url, data })}`;
  const renderEpisodes = () => {
    const box = $view.querySelector("#episodes");
    if (!box) return;
    box.innerHTML = (groupNames.length > 1 ? `<div class="chips">${groupNames.map((g) => `<button class="chip ${g === group ? "on" : ""}" data-g="${esc(g)}">${esc(g)} · ${groups[g].length}</button>`).join("")}</div>` : "") +
      groups[group].map((e) => `<a class="ep" href="${watch(e.data)}"><div class="thumb">${e.poster ? `<img loading="lazy" src="${esc(e.poster)}" alt="">` : `<div class="noimg">${esc(e.label)}</div>`}
        ${e.progress > 0.02 ? `<div class="bar-progress"><i style="width:${Math.round(Math.min(e.progress, 1) * 100)}%"></i></div>` : ""}</div>
        <div class="t"><div class="code">${esc(e.episode != null ? `E${e.episode}` : e.label)}</div><div class="n">${esc(e.name || e.label)}</div>
        ${e.progress > 0.92 ? '<div class="done">✓ Watched</div>' : e.description ? `<div class="d">${esc(e.description)}</div>` : ""}</div></a>`).join("");
    box.querySelectorAll("[data-g]").forEach((b) => b.addEventListener("click", () => { group = b.dataset.g; renderEpisodes(); }));
  };
  $view.innerHTML = `<div class="page">
    <div class="title-hero">${t.backdrop ? `<img src="${esc(t.backdrop)}" alt="">` : ""}</div>
    <button class="icon-btn back" onclick="history.length > 1 ? history.back() : (location.hash='#/home')">${icons.back}</button>
    <div class="title-info"><h1>${esc(t.name)}</h1>
      <div class="meta">${t.rating ? `<span class="tag accent">★ ${t.rating.toFixed(1)}</span>` : ""}${t.year ? `<span>${t.year}</span>` : ""}
        ${t.contentRating ? `<span class="tag">${esc(t.contentRating)}</span>` : ""}${t.duration ? `<span>${t.duration} min</span>` : ""}
        ${t.series ? `<span>${t.episodes.length} episodes</span>` : ""}<span class="tag">${esc(t.api)}</span></div>
      <div class="title-actions">
        ${t.resume ? `<a class="btn white wide" href="${watch(t.resume.data)}">${icons.play} ${esc(resumeText)}</a>` : `<button class="btn wide" disabled>Nothing to play</button>`}
        <div class="row2">
          <button class="btn ${t.bookmarked ? "on" : ""}" id="bm">${t.bookmarked ? icons.check : icons.add}<span>My list</span></button>
          <button class="btn" id="seen">${icons.check}<span>Watched</span></button>
          <a class="btn" href="#/search?${qs({ q: t.name })}">${icons.search}<span>Search all</span></a>
        </div>
      </div>
      ${t.plot ? `<p class="plot clamped">${esc(t.plot)}</p>` : ""}
      ${t.tags.length ? `<div class="meta">${t.tags.map((g) => `<span class="tag">${esc(g)}</span>`).join("")}</div>` : ""}
      ${t.series ? `<h2>Episodes</h2><div id="episodes"></div>` : ""}
      ${shelf("More like this", t.recommendations)}
    </div></div>`;
  renderEpisodes();
  const plot = $view.querySelector(".plot");
  plot && plot.addEventListener("click", () => plot.classList.toggle("clamped"));
  $view.querySelector("#bm").addEventListener("click", async (e) => {
    const r = await api("/api/bookmark", { api: t.api, url: t.url, name: t.name, poster: t.poster, type: t.type });
    e.currentTarget.classList.toggle("on", r.bookmarked);
    e.currentTarget.innerHTML = `${r.bookmarked ? icons.check : icons.add}<span>My list</span>`;
    toast(r.bookmarked ? "Added to My list" : "Removed from My list");
  });
  $view.querySelector("#seen").addEventListener("click", async () => {
    await api("/api/watched", { api: t.api, url: t.url, name: t.name, poster: t.poster, watched: true, episodes: t.episodes.map((e) => ({ data: e.data, label: e.label })) });
    toast("Marked as watched"); title(params);
  });
}

// ---------- player ----------

async function watch(params) {
  const key = params.api + "\n" + params.url;
  const live = params.live === "1";
  document.body.style.overflow = "hidden";
  $view.innerHTML = `<div class="player">
    <div class="top"><button class="icon-btn" id="close">${icons.back}</button><div class="t"><b id="ptitle">${esc(params.name || "")}</b><span class="muted small" id="psource">Finding sources…</span></div></div>
    <video playsinline controls autoplay preload="auto"></video><div class="status" id="pstatus"><div class="spinner"></div>Finding sources…</div>
    <div class="bottom"><button class="btn small" id="srcs">${icons.list} Sources</button><button class="btn small" id="subs">${icons.subs} Subtitles</button>
      <button class="btn small" id="nextBtn" hidden>${icons.next} Next episode</button></div></div>`;
  const video = $view.querySelector("video");
  const status = $view.querySelector("#pstatus");
  const setStatus = (html) => { status.innerHTML = html; status.hidden = !html; };
  let saveTimer, countdown;
  const leave = () => { saveProgress(); clearInterval(saveTimer); clearInterval(countdown); document.body.style.overflow = ""; };
  $view.querySelector("#close").addEventListener("click", () => { leave(); history.length > 1 ? history.back() : (location.hash = "#/home"); });
  window.addEventListener("hashchange", function off() { leave(); window.removeEventListener("hashchange", off); });
  window.addEventListener("pagehide", () => saveProgress());

  let links = [], subs = [], current = -1, t = null, ep = null, next = null;
  if (live) {
    try {
      const l = await api(`/api/live/play?${qs({ key: params.key })}`);
      links = [{ name: "Live", url: l.url, hls: l.hls }];
      $view.querySelector("#ptitle").textContent = l.name;
    } catch (e) { return setStatus(esc(e.message)); }
  } else {
    t = state.titles[key] || await api(`/api/load?${qs({ api: params.api, url: params.url })}`);
    state.titles[key] = t;
    const i = t.episodes.findIndex((e) => e.data === params.data);
    ep = t.episodes[i] || t.episodes[0];
    next = i >= 0 ? t.episodes[i + 1] : null;
    $view.querySelector("#ptitle").textContent = t.series ? `${t.name} · ${ep.label}` : t.name;
    const nb = $view.querySelector("#nextBtn");
    if (next) { nb.hidden = false; nb.addEventListener("click", () => goNext()); }
    const r = await api(`/api/links?${qs({ api: params.api, data: params.data })}`);
    links = r.links; subs = r.subtitles;
    if (!links.length) return setStatus(`<b>No playable source</b><br>${esc(r.error || "")}`);
  }

  const startAt = live ? 0 : (ep && ep.progress < 0.92 && ep.position > 10 ? ep.position : 0);
  let resumeAt = startAt;
  const play = (i) => {
    current = i;
    const l = links[i];
    $view.querySelector("#psource").textContent = `${l.name}${l.mkv ? " · MKV (may not play on iPhone)" : ""} · ${i + 1}/${links.length}`;
    setStatus('<div class="spinner"></div>Loading…');
    if (video.currentTime > 5) resumeAt = video.currentTime;
    video.querySelectorAll("track").forEach((tr) => tr.remove());
    video.src = l.url;
    subs.forEach((s, n) => video.insertAdjacentHTML("beforeend", `<track kind="subtitles" label="${esc(s.lang)}" srclang="${esc((s.lang || "en").slice(0, 2).toLowerCase())}" src="${esc(s.url)}"${n === 0 && localStorage.getItem("subs") === "on" ? " default" : ""}>`));
    video.load();
    video.play().catch(() => setStatus("Tap ▶ to start"));
  };
  video.addEventListener("loadedmetadata", () => { if (resumeAt > 5 && isFinite(video.duration) && resumeAt < video.duration - 30) video.currentTime = resumeAt; });
  video.addEventListener("playing", () => setStatus(""));
  video.addEventListener("waiting", () => setStatus('<div class="spinner"></div>'));
  // A source that fails moves on to the next one, keeping the position (never to the next episode).
  video.addEventListener("error", () => {
    if (current + 1 < links.length) { toast(`${links[current].name} failed, trying the next source`); play(current + 1); }
    else setStatus("<b>None of the sources would play.</b><br>Try another extension or pick a source.");
  });
  video.addEventListener("timeupdate", () => {
    if (!next || !isFinite(video.duration) || video.duration < 300 || countdown) return;
    if (video.currentTime > video.duration - 20) showNext();
  });
  video.addEventListener("ended", () => { saveProgress(true); if (next && isFinite(video.duration) && video.duration >= 180 && video.currentTime >= video.duration - 30) showNext(); });

  function showNext() {
    if (countdown || !next) return;
    let n = 10;
    const card = document.createElement("div");
    card.className = "next-card";
    card.innerHTML = `<div class="label">NEXT EPISODE</div><b>${esc(next.label)}${next.name && next.name !== next.label ? " · " + esc(next.name) : ""}</b>
      <div class="muted small" id="cd">Starts in ${n} s</div><div class="actions"><button class="btn primary small" id="now">${icons.play} Play now</button><button class="btn small" id="cancel">Cancel</button></div>`;
    $view.querySelector(".player").appendChild(card);
    card.querySelector("#now").addEventListener("click", goNext);
    card.querySelector("#cancel").addEventListener("click", () => { clearInterval(countdown); card.remove(); countdown = "cancelled"; });
    countdown = setInterval(() => { n--; const cd = card.querySelector("#cd"); if (cd) cd.textContent = `Starts in ${n} s`; if (n <= 0) goNext(); }, 1000);
  }
  function goNext() {
    clearInterval(countdown); saveProgress();
    location.replace(`#/watch?${qs({ api: params.api, url: params.url, data: next.data })}`);
  }
  function saveProgress(finished) {
    if (live || !t || !ep || !isFinite(video.duration) || video.duration <= 0 || video.currentTime < 5) return;
    const position = finished ? video.duration : video.currentTime;
    navigator.sendBeacon ? navigator.sendBeacon("/api/progress", new Blob([JSON.stringify({
      api: t.api, url: t.url, name: t.name, poster: t.poster, data: ep.data, label: t.series ? ep.label : "", position, duration: video.duration,
    })], { type: "application/json" })) : null;
    ep.position = position; ep.progress = position / video.duration;
  }
  saveTimer = setInterval(() => { if (!video.paused) saveProgress(); }, 10000);
  video.addEventListener("pause", () => saveProgress());

  $view.querySelector("#srcs").addEventListener("click", () => sheet("Sources", links.map((l, i) =>
    `<button class="option ${i === current ? "on" : ""}" data-i="${i}">${esc(l.name)}<span class="spacer"></span><span class="muted small">${l.hls ? "HLS" : l.mkv ? "MKV" : "File"}</span></button>`).join(""), (el, close) => {
    el.querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", () => { close(); play(+b.dataset.i); }));
  }));
  $view.querySelector("#subs").addEventListener("click", () => {
    const tracks = [...video.textTracks];
    if (!tracks.length) return toast("No subtitles from this extension");
    sheet("Subtitles", `<button class="option" data-i="-1">Off</button>` + tracks.map((tr, i) =>
      `<button class="option ${tr.mode === "showing" ? "on" : ""}" data-i="${i}">${esc(tr.label || `Track ${i + 1}`)}</button>`).join(""), (el, close) => {
      el.querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", () => {
        const i = +b.dataset.i;
        tracks.forEach((tr, n) => (tr.mode = n === i ? "showing" : "disabled"));
        localStorage.setItem("subs", i >= 0 ? "on" : "off");
        close();
      }));
    });
  });
  play(0);
}

// ---------- Live TV ----------

async function live(params) {
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Live TV</h1></div><div class="spinner"></div></div>`;
  state.live = state.live || await api("/api/live");
  const channels = state.live.channels;
  if (!channels.length) { $view.querySelector(".spinner").outerHTML = `<div class="empty"><b>No channels</b>Add an M3U playlist or Xtream login in Live TV sources in the app on the server.</div>`; return; }
  const groups = ["All", "★ Favourites", ...[...new Set(channels.map((c) => c.group))]];
  let group = params.g || "All", q = "";
  $view.querySelector(".page").innerHTML = `<div class="bar"><h1>Live TV</h1></div>
    <input class="field" type="search" placeholder="Search channels" autocomplete="off">
    <div class="chips" style="margin-top:10px">${groups.map((g) => `<button class="chip ${g === group ? "on" : ""}" data-g="${esc(g)}">${esc(g)}</button>`).join("")}</div><div id="chs"></div>`;
  const list = $view.querySelector("#chs");
  const render = () => {
    const shown = channels.filter((c) => (group === "All" || (group === "★ Favourites" ? c.favourite : c.group === group)) && (!q || c.name.toLowerCase().includes(q))).slice(0, 400);
    list.innerHTML = shown.map((c) => `<a class="ch" href="#/watch?${qs({ live: 1, key: c.key, name: c.name })}">
      ${c.logo ? `<div class="logo"><img loading="lazy" src="${esc(c.logo)}" alt="" onerror="this.parentNode.className='logo none';this.replaceWith(${esc(JSON.stringify(c.name.slice(0, 2).toUpperCase()))})"></div>` : `<div class="logo none">${esc(c.name.slice(0, 2).toUpperCase())}</div>`}
      <div class="t"><b>${c.favourite ? '<span class="star">★</span> ' : ""}${esc(c.name)}</b><div class="now">${c.now ? `Now: ${esc(c.now.title)} · until ${hhmm(c.now.stop)}` : esc(c.group)}</div></div></a>`).join("") || `<div class="empty">No channels match.</div>`;
  };
  $view.querySelector("input").addEventListener("input", (e) => { q = e.target.value.trim().toLowerCase(); render(); });
  $view.querySelectorAll("[data-g]").forEach((b) => b.addEventListener("click", () => {
    group = b.dataset.g; $view.querySelectorAll("[data-g]").forEach((x) => x.classList.toggle("on", x === b)); render();
  }));
  render();
}

// ---------- library ----------

async function library(params) {
  const tab = params.tab || "continue";
  $view.innerHTML = `<div class="page"><div class="bar"><h1>Library</h1></div><div class="spinner"></div></div>`;
  const lib = await api("/api/library");
  const tabs = { continue: "Continue watching", list: "My list", history: "History" };
  let body;
  if (tab === "list") body = lib.bookmarks.length ? `<div class="grid">${lib.bookmarks.map((b) => poster(b)).join("")}</div>` : `<div class="empty"><b>Your list is empty</b>Use “My list” on a title to keep it here.</div>`;
  else {
    const items = (tab === "history" ? lib.history : lib.continue).map((e) => ({ ...e, sub: [e.label, e.progress > 0.92 ? "Watched" : `${clock(e.duration - e.position)} left`].filter(Boolean).join(" · ") }));
    body = items.length ? `<div class="grid">${items.map((i) => poster(i)).join("")}</div>` : `<div class="empty"><b>Nothing yet</b>What you watch shows up here, also from the app on your computer.</div>`;
  }
  $view.querySelector(".page").innerHTML = `<div class="bar"><h1>Library</h1></div>
    <div class="chips">${Object.entries(tabs).map(([k, v]) => `<a class="chip ${k === tab ? "on" : ""}" href="#/library?tab=${k}">${esc(v)}</a>`).join("")}</div><div style="margin-top:14px">${body}</div>`;
}

// ---------- more ----------

async function more() {
  state.me = await api("/api/me");
  $view.innerHTML = `<div class="page"><div class="bar"><h1>More</h1></div>
    <div class="list">
      <div>${avatar(state.me.profile, "sm")}<span class="spacer">${esc(state.me.profile.name)}</span><span class="muted small">this profile</span></div>
      <a href="#/profiles">Switch profile</a>
      <button id="logout">Log out of this device</button>
    </div>
    <h2>Add to Home Screen</h2>
    <p class="muted small">In Safari tap <b>Share</b> → <b>Add to Home Screen</b>. CloudStream then opens full screen like an app.</p>
    <h2>About</h2>
    <p class="muted small">CloudStream web app ${esc(state.me.version)}. Your own server runs the extensions; extensions, repositories and settings are managed in the CloudStream app on it. Watch progress and My list are shared with it.</p>
  </div>`;
  $view.querySelector("#logout").addEventListener("click", async () => { await api("/api/logout", {}); state.me = null; location.hash = "#/login"; });
}

router();
