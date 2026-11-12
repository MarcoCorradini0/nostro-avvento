"use strict";

// =============================================================================
// Chiave personale
// =============================================================================
// Il link che mandi e' tipo https://sito/?k=CHIAVE. La salviamo nel telefono
// e la togliamo dalla barra degli indirizzi (cosi' non finisce negli screenshot).
const KEY_STORAGE = "avvento.key";
const OPENED_STORAGE = "avvento.opened";

function readKey() {
  const fromUrl = new URLSearchParams(location.search).get("k");
  if (fromUrl) {
    try { localStorage.setItem(KEY_STORAGE, fromUrl); } catch (_) {}
    history.replaceState(null, "", location.pathname);
    return fromUrl;
  }
  try { return localStorage.getItem(KEY_STORAGE); } catch (_) { return null; }
}

const key = readKey();
const API = (window.AVVENTO_API || "").replace(/\/$/, "");
const $ = id => document.getElementById(id);
const sleep = ms => new Promise(r => setTimeout(r, ms));

// Stato della pagina
const state = { me: null, names: {}, today: null, meetings: [], presence: { online: [], lastSeen: {} } };

// =============================================================================
// API
// =============================================================================
// Sul piano free di Render il server dorme dopo 15 minuti e ci mette un po'
// a ripartire. Per le letture riproviamo con calma (e mostriamo il loader).
const WAKE_TIMEOUT_MS = 120000;
const RETRY_MS = 3000;

async function api(path) {
  const deadline = Date.now() + WAKE_TIMEOUT_MS;
  for (;;) {
    try {
      const res = await fetch(API + path, { headers: { "X-Key": key || "" } });
      if (res.status === 401) throw new Error("unauthorized");
      const isJson = (res.headers.get("content-type") || "").includes("application/json");
      if (res.ok && isJson) return res.json();
    } catch (e) {
      if (e.message === "unauthorized") throw e;
      // errore di rete: il server sta ancora dormendo, riproviamo
    }
    if (Date.now() > deadline) throw new Error("timeout");
    await sleep(RETRY_MS);
  }
}

// Le scritture partono una volta sola: se falliscono lo diciamo.
async function apiPost(path, body) {
  const res = await fetch(API + path, {
    method: "POST",
    headers: { "X-Key": key || "", "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : "{}",
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Qualcosa e' andato storto");
  return data;
}

// =============================================================================
// Loader, toast, errori
// =============================================================================
const LOADER_MESSAGES = [
  "Sto svegliando il calendario…",
  "Le renne si stanno allacciando le scarpe 🦌",
  "Scaldo la cioccolata ☕",
  "Ancora un attimo, quasi pronto ✨",
];

function startLoader() {
  let i = 0;
  // Lo mostriamo solo se serve: se il server risponde subito, niente flash.
  const show = setTimeout(() => { $("loader").hidden = false; }, 700);
  const rotate = setInterval(() => {
    i = (i + 1) % LOADER_MESSAGES.length;
    $("loader-text").textContent = LOADER_MESSAGES[i];
  }, 5000);
  return () => { clearTimeout(show); clearInterval(rotate); $("loader").hidden = true; };
}

function toast(msg, ms = 2600) {
  const el = $("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove("show"), ms);
}

function showError(msg) {
  $("error").textContent = msg;
  $("error").hidden = false;
}

// =============================================================================
// Date in italiano
// =============================================================================
function parseDay(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}
const fmtLong = new Intl.DateTimeFormat("it-IT", { weekday: "long", day: "numeric", month: "long" });
const fmtShort = new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "long" });
const fmtWhen = new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

function ago(isoInstant) {
  const min = Math.round((Date.now() - new Date(isoInstant)) / 60000);
  if (min < 1) return "vista ora";
  if (min < 60) return `vista ${min} min fa`;
  const h = Math.round(min / 60);
  if (h < 24) return h === 1 ? "vista un'ora fa" : `vista ${h} ore fa`;
  return "vista " + fmtShort.format(new Date(isoInstant));
}

// =============================================================================
// Calendario dell'avvento
// =============================================================================
// Ordine "mescolato" fisso, come nei calendari veri.
// 4 caselle grandi (occupano 2 posti): 24 + 4 = 28 posti = 7 righe piene da 4.
const ORDER = [7, 15, 1, 22, 11, 4, 18, 24, 9, 2, 13, 20, 5, 16, 23, 10, 3, 19, 8, 14, 21, 6, 12, 17];
const BIG = new Set([24, 13, 1, 19]);
const CHRISTMAS = 25;
const ICONS = { text: "💌", song: "🎵", voucher: "🎟️", question: "❓", movie: "🎬", recipe: "🍪", christmas: "🎄" };

function openedSet() {
  try { return new Set(JSON.parse(localStorage.getItem(OPENED_STORAGE) || "[]")); }
  catch (_) { return new Set(); }
}
function markOpened(day) {
  const s = openedSet();
  s.add(day);
  try { localStorage.setItem(OPENED_STORAGE, JSON.stringify([...s])); } catch (_) {}
}

function daysUntil(day, todayIso) {
  const today = parseDay(todayIso);
  return Math.round((new Date(today.getFullYear(), 11, day) - today) / 86400000);
}

function renderAdvent(data) {
  const grid = $("grid");
  const byDay = new Map(data.doors.map(d => [d.day, d]));
  const opened = openedSet();
  grid.replaceChildren();

  // La casella di Natale: il server la manda solo dal 25 dicembre.
  const xmas = byDay.get(CHRISTMAS);
  if (xmas) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "door christmas" + (opened.has(CHRISTMAS) ? " opened" : "");
    btn.setAttribute("aria-label", "Casella speciale di Natale, apri");
    const star = document.createElement("span");
    star.textContent = "⭐ 25";
    const label = document.createElement("small");
    label.textContent = "Buon Natale";
    btn.append(star, label);
    btn.addEventListener("click", () => openDoor(xmas, btn));
    grid.appendChild(btn);
  }

  for (const day of ORDER) {
    const door = byDay.get(day);
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "door";
    btn.textContent = day;
    if (BIG.has(day)) btn.classList.add("big");

    if (!door.unlocked) {
      btn.classList.add("locked");
      btn.setAttribute("aria-label", "Casella " + day + ", ancora chiusa");
      btn.addEventListener("click", () => {
        btn.classList.remove("shake");
        void btn.offsetWidth; // fa ripartire l'animazione
        btn.classList.add("shake");
        const n = daysUntil(day, data.today);
        toast(n === 1 ? "Si apre domani ✨" : "Ancora " + n + " giorni... pazienza 😌");
      });
    } else {
      btn.classList.add(opened.has(day) ? "opened" : "ready");
      btn.setAttribute("aria-label", "Casella " + day + ", apri");
      btn.addEventListener("click", () => openDoor(door, btn));
    }
    grid.appendChild(btn);
  }
}

function openDoor(door, btn) {
  const c = door.content;
  $("dlg-day").textContent = door.day + " dicembre";
  $("dlg-icon").textContent = (c && ICONS[c.type]) || "🎁";
  // textContent (mai innerHTML): il testo viene mostrato cosi' com'e', nessun codice iniettato
  $("dlg-title").textContent = (c && c.title) || "Sorpresa in arrivo...";
  $("dlg-text").textContent = (c && c.text) || "Questa casella e' ancora vuota, ci sto lavorando 😅";

  const link = $("dlg-link");
  if (c && c.url && c.url.startsWith("https://")) {
    link.href = c.url;
    link.textContent = c.type === "song" ? "Ascolta 🎵" : "Apri 🔗";
    link.hidden = false;
  } else {
    link.hidden = true;
  }

  $("door-dialog").showModal();
  markOpened(door.day);
  btn.classList.remove("ready");
  btn.classList.add("opened");
  if (door.day === CHRISTMAS) { state.christmas = true; updateSnow(); }
}

$("dlg-close").addEventListener("click", () => $("door-dialog").close());
$("door-dialog").addEventListener("click", e => { if (e.target === $("door-dialog")) $("door-dialog").close(); });

// =============================================================================
// Chi e' online (WebSocket)
// =============================================================================
let ws = null;
let wsRetry = 0;
let wsTimer = null;

function wsUrl() {
  const base = API || location.origin;
  return base.replace(/^http/, "ws") + "/ws";
}

function connect() {
  if (ws || document.hidden || !state.me) return;
  clearTimeout(wsTimer);
  const socket = new WebSocket(wsUrl());
  ws = socket;
  socket.onopen = () => { wsRetry = 0; socket.send(JSON.stringify({ key })); };
  socket.onmessage = e => {
    let msg;
    try { msg = JSON.parse(e.data); } catch (_) { return; }
    if (msg.type === "presence") { state.presence = msg; renderPresence(); }
    if (msg.type === "meeting") onMeetingNotice(msg);
  };
  socket.onclose = () => {
    if (ws === socket) ws = null;
    // Riconnessione con attesa crescente (1s, 2s, 4s… max 30s), solo se la pagina e' visibile.
    if (!document.hidden) {
      wsRetry++;
      wsTimer = setTimeout(connect, Math.min(30000, 1000 * 2 ** (wsRetry - 1)));
    }
  };
}

// Quando lei chiude l'app o cambia scheda, risulta offline. Quando torna, ci riconnettiamo.
document.addEventListener("visibilitychange", () => {
  if (!state.me) return;
  if (document.hidden) {
    clearTimeout(wsTimer);
    if (ws) { const s = ws; ws = null; s.close(1000); }
  } else {
    connect();
    refreshMeetings();
  }
});

function bothOnline() {
  const on = state.presence.online;
  return state.me && on.includes(state.me.id) && on.includes(state.me.otherId);
}

function renderPresence() {
  const box = $("presence");
  if (!state.me || !state.me.otherId) return;
  box.replaceChildren();
  for (const id of [state.me.id, state.me.otherId]) {
    const online = state.presence.online.includes(id);
    const chip = document.createElement("span");
    chip.className = "person";
    const dot = document.createElement("i");
    dot.className = "dot" + (online ? " on" : "");
    const name = document.createElement("b");
    name.textContent = state.names[id];
    chip.append(dot, name);
    if (!online && id !== state.me.id) {
      const seen = state.presence.lastSeen[id];
      chip.append(" " + (seen ? ago(seen) : "offline"));
    }
    box.appendChild(chip);
  }
  box.hidden = false;

  const together = bothOnline();
  if (together && !state.together) toast("❄️ Siete online insieme: nevica!", 3500);
  state.together = together;
  updateSnow();
}

// Aggiorna "vista X min fa" senza aspettare messaggi nuovi.
setInterval(() => { if (state.me) renderPresence(); }, 60000);

// =============================================================================
// Incontri
// =============================================================================
const KIND_TEXT = {
  PROPOSED: "ha proposto", MODIFIED: "ha proposto una modifica",
  ACCEPTED: "ha accettato", DECLINED: "ha rifiutato", CANCELLED: "ha annullato",
};
const NOTICE = {
  PROPOSED: (n, d) => `💌 ${n} ti propone di vedervi ${d}`,
  MODIFIED: (n, d) => `✏️ ${n} propone una modifica: ${d}`,
  ACCEPTED: (n, d) => `✅ ${n} ha accettato: ${d}!`,
  DECLINED: (n, d) => `😔 ${n} non riesce ${d}`,
  CANCELLED: (n, d) => `🚫 ${n} ha annullato ${d}`,
};

function isTodo(m) { return m.status === "PENDING" && m.waitingFor === state.me.id; }
function isOpen(m) { return m.status === "PENDING" || m.status === "ACCEPTED"; }
function isPast(m) { return m.date < state.today; }

async function refreshMeetings() {
  if (!state.me) return;
  try {
    const data = await api("/api/meetings");
    state.meetings = data.meetings;
    renderMeetings();
  } catch (_) { /* riproveremo al prossimo avviso */ }
}

function onMeetingNotice(msg) {
  const text = NOTICE[msg.kind](state.names[msg.by], "il " + fmtShort.format(parseDay(msg.date)));
  toast(text, 5000);
  refreshMeetings();
}

function renderMeetings() {
  const todo = state.meetings.filter(isTodo).length;
  $("badge").textContent = todo;
  $("badge").hidden = todo === 0;

  // Prima quelli a cui devo rispondere, poi i prossimi per data, in fondo passati/chiusi.
  const rank = m => (isTodo(m) ? 0 : isOpen(m) && !isPast(m) ? 1 : 2);
  const list = [...state.meetings].sort((a, b) => rank(a) - rank(b) || a.date.localeCompare(b.date));

  const box = $("meetings");
  box.replaceChildren();
  if (list.length === 0) {
    const p = document.createElement("p");
    p.className = "empty";
    p.textContent = "Ancora nessun incontro in programma. Proponi tu il primo giorno 💫";
    box.appendChild(p);
    return;
  }
  for (const m of list) box.appendChild(meetingCard(m));
}

function meetingCard(m) {
  const other = state.names[state.me.otherId];
  const card = document.createElement("article");
  card.className = "meeting";
  if (isTodo(m)) card.classList.add("todo");
  if (m.status === "ACCEPTED") card.classList.add("accepted");
  if (!isOpen(m)) card.classList.add("closed");
  if (isPast(m)) card.classList.add("past");

  const head = document.createElement("div");
  head.className = "m-head";
  const date = document.createElement("span");
  date.className = "m-date";
  date.textContent = fmtLong.format(parseDay(m.date));
  const chip = document.createElement("span");
  chip.className = "chip";
  chip.textContent =
    m.status === "ACCEPTED" ? "Confermato ✅" :
    m.status === "DECLINED" ? "Rifiutato" :
    m.status === "CANCELLED" ? "Annullato" :
    isTodo(m) ? "Tocca a te" : `In attesa di ${other}`;
  head.append(date, chip);
  card.appendChild(head);

  if (m.text) {
    const text = document.createElement("p");
    text.className = "m-text";
    text.textContent = m.text;
    card.appendChild(text);
  }

  // La "chat" dell'incontro
  const hist = document.createElement("details");
  hist.className = "m-history";
  const sum = document.createElement("summary");
  const last = m.events[m.events.length - 1];
  sum.textContent = `${state.names[last.by]} ${KIND_TEXT[last.kind]} · ${fmtWhen.format(new Date(last.at))}`;
  const ul = document.createElement("ul");
  for (const ev of m.events) {
    const li = document.createElement("li");
    let line = `${state.names[ev.by]} ${KIND_TEXT[ev.kind]}`;
    if (ev.date) line += ` (${fmtShort.format(parseDay(ev.date))})`;
    li.textContent = `${fmtWhen.format(new Date(ev.at))} — ${line}`;
    ul.appendChild(li);
  }
  hist.append(sum, ul);
  card.appendChild(hist);

  // Bottoni: dipendono da chi deve rispondere
  const actions = document.createElement("div");
  actions.className = "m-actions";
  const add = (label, cls, fn) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "btn " + cls;
    b.textContent = label;
    b.addEventListener("click", fn);
    actions.appendChild(b);
  };
  if (isTodo(m)) {
    add("Accetta", "", () => act(m, "accept", "Evviva! ✅"));
    add("Modifica", "ghost", () => openMeetingForm(m));
    add("Rifiuta", "secondary", () => confirm("Rifiutare questa proposta?") && act(m, "decline", "Proposta rifiutata"));
  } else if (isOpen(m) && !isPast(m)) {
    add("Modifica", "ghost", () => openMeetingForm(m));
    add("Annulla", "secondary", () => confirm("Annullare questo incontro?") && act(m, "cancel", "Incontro annullato"));
  }
  if (actions.childElementCount) card.appendChild(actions);
  return card;
}

async function act(m, action, okMsg) {
  try {
    await apiPost(`/api/meetings/${encodeURIComponent(m.id)}/${action}`);
    toast(okMsg);
    refreshMeetings();
  } catch (e) {
    toast("⚠️ " + e.message, 4000);
  }
}

// ---- Form proposta / modifica ------------------------------------------------
const MAX_WORDS = 200;
let editing = null;

function words(text) { return text.split(/\s+/).filter(Boolean).length; }

function updateCounter() {
  const n = words($("mf-text").value);
  $("mf-count").textContent = `${n} / ${MAX_WORDS} parole`;
  $("mf-count").classList.toggle("over", n > MAX_WORDS);
  $("mf-send").disabled = n > MAX_WORDS;
}

function openMeetingForm(meeting) {
  editing = meeting || null;
  $("mf-title").textContent = editing ? "Proponi una modifica" : "Proponi un giorno";
  $("mf-date").min = state.today;
  $("mf-date").value = editing ? editing.date : "";
  $("mf-text").value = editing ? editing.text : "";
  updateCounter();
  $("meeting-dialog").showModal();
}

$("mf-text").addEventListener("input", updateCounter);
$("mf-cancel").addEventListener("click", () => $("meeting-dialog").close());
$("new-meeting").addEventListener("click", () => openMeetingForm(null));
$("meeting-form").addEventListener("submit", async e => {
  e.preventDefault();
  const body = { date: $("mf-date").value, text: $("mf-text").value.trim() };
  if (!body.date) return;
  $("mf-send").disabled = true;
  try {
    if (editing) await apiPost(`/api/meetings/${encodeURIComponent(editing.id)}/modify`, body);
    else await apiPost("/api/meetings", body);
    $("meeting-dialog").close();
    toast(`📨 Inviato a ${state.names[state.me.otherId]}!`);
    refreshMeetings();
  } catch (err) {
    toast("⚠️ " + err.message, 4000);
  } finally {
    $("mf-send").disabled = false;
  }
});

// =============================================================================
// Tab
// =============================================================================
function showView(name) {
  $("view-advent").hidden = name !== "advent";
  $("view-meetings").hidden = name !== "meetings";
  for (const b of $("tabs").querySelectorAll("button")) b.classList.toggle("active", b.dataset.view === name);
  try { sessionStorage.setItem("avvento.view", name); } catch (_) {}
}
for (const b of $("tabs").querySelectorAll("button")) b.addEventListener("click", () => showView(b.dataset.view));

// =============================================================================
// Neve
// =============================================================================
// Una sola animazione; setSnow(n) cambia quanti fiocchi ci sono.
const snow = { flakes: [], running: false, w: 0, h: 0 };
const dpr = window.devicePixelRatio || 1;

function newFlake(randomY) {
  return {
    x: Math.random() * snow.w,
    y: randomY ? Math.random() * snow.h : -5,
    r: (Math.random() * 2 + 1) * dpr,
    speed: Math.random() * 0.6 + 0.3,
    phase: Math.random() * Math.PI * 2,
  };
}

function setSnow(count) {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const canvas = $("snow");

  if (!snow.running) {
    snow.running = true;
    const ctx = canvas.getContext("2d");
    const resize = () => {
      snow.w = canvas.width = innerWidth * dpr;
      snow.h = canvas.height = innerHeight * dpr;
    };
    resize();
    addEventListener("resize", resize);

    (function frame() {
      ctx.clearRect(0, 0, snow.w, snow.h);
      ctx.fillStyle = "rgba(255,255,255,.8)";
      ctx.beginPath(); // un solo path per tutti i fiocchi: un solo disegno per frame
      for (const f of snow.flakes) {
        f.y += f.speed * dpr;
        f.phase += 0.01;
        f.x += Math.sin(f.phase) * 0.4 * dpr;
        if (f.y > snow.h) Object.assign(f, newFlake(false));
        ctx.moveTo(f.x + f.r, f.y);
        ctx.arc(f.x, f.y, f.r, 0, Math.PI * 2);
      }
      ctx.fill();
      requestAnimationFrame(frame);
    })();
  }

  while (snow.flakes.length < count) snow.flakes.push(newFlake(true));
  snow.flakes.length = count;
}

// Neve leggera da soli, forte quando siete online insieme, fortissima a Natale.
function updateSnow() {
  setSnow(state.christmas ? 160 : bothOnline() ? 120 : 40);
}

// =============================================================================
// Avvio
// =============================================================================
async function init() {
  if (!key) {
    showError("Per entrare apri il link personale che hai ricevuto 💌");
    return;
  }
  setSnow(40);
  const stopLoader = startLoader();
  try {
    // Le tre chiamate partono insieme: meno attesa.
    const [me, advent, meetings] = await Promise.all([api("/api/me"), api("/api/advent"), api("/api/meetings")]);
    stopLoader();
    state.me = me;
    state.names = { [me.id]: me.name, [me.otherId]: me.otherName };
    state.today = advent.today;
    state.meetings = meetings.meetings;
    $("greeting").textContent = "Ciao " + me.name + " ❄️";
    renderAdvent(advent);
    renderMeetings();
    $("tabs").hidden = false;
    let view = "advent";
    try { view = sessionStorage.getItem("avvento.view") || view; } catch (_) {}
    showView(view);
    connect();
  } catch (e) {
    stopLoader();
    showError(e.message === "unauthorized"
      ? "Questo link non e' valido 🤔"
      : "Non riesco a collegarmi, riprova tra poco.");
  }
}

init();
