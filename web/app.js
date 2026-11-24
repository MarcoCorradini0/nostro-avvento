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
const state = { me: null, names: {}, today: null, meetings: [], photos: [], presence: { online: [], lastSeen: {} } };

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
const ICONS = { text: "💌", quote: "✨", song: "🎵", voucher: "🎟️", question: "❓", movie: "🎬", recipe: "🍪", christmas: "🎄" };
const LINK_LABELS = { song: "Ascolta 🎵", christmas: "Ascolta 🎵", movie: "Scopri il film 🎬", recipe: "Guarda la ricetta 🍳" };

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
  // Se la casella ha una foto, la foto prende il posto di icona e animazione.
  const hasAnim = renderAnim(door.photo ? null : c && c.anim);
  $("dlg-icon").hidden = hasAnim || Boolean(door.photo);
  // textContent (mai innerHTML): il testo viene mostrato cosi' com'e', nessun codice iniettato
  $("dlg-title").textContent = (c && c.title) || "Sorpresa in arrivo...";
  $("dlg-text").textContent = (c && c.text) || "Questa casella e' ancora vuota, ci sto lavorando 😅";

  const link = $("dlg-link");
  if (c && c.url && c.url.startsWith("https://")) {
    link.href = c.url;
    link.textContent = LINK_LABELS[c.type] || "Apri 🔗";
    link.hidden = false;
  } else {
    link.hidden = true;
  }

  const photo = $("dlg-photo");
  photo.hidden = true;
  photo.removeAttribute("src");
  if (door.photo) photoSrc(door.photo).then(src => { photo.src = src; photo.hidden = false; }).catch(() => {});

  $("door-dialog").showModal();
  markOpened(door.day);
  btn.classList.remove("ready");
  btn.classList.add("opened");
  if (door.day === CHRISTMAS) { state.christmas = true; updateSnow(); }
}

// ---- Animazioni del popup (solo CSS + emoji: niente GIF esterne, niente innerHTML) ----
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text) e.textContent = text;
  return e;
}

const ANIMS = {
  // I nostri colori: 💙 Marco, 💜 Esther
  // un cuore che batte
  beat: box => box.append(el("span", "a-beat", "💜")),
  // cuori che salgono da sotto
  hearts: box => {
    const kinds = ["💙", "💜"];
    for (let i = 0; i < 8; i++) {
      const h = el("span", "a-float", kinds[i % kinds.length]);
      h.style.setProperty("--x", (5 + Math.random() * 90).toFixed(0) + "%");
      h.style.setProperty("--d", (Math.random() * 3).toFixed(2) + "s");
      h.style.setProperty("--t", (2.6 + Math.random() * 1.8).toFixed(2) + "s");
      h.style.setProperty("--s", (0.9 + Math.random() * 0.9).toFixed(2) + "rem");
      box.append(h);
    }
  },
  // l'aereo che va dall'Italia al Peru'
  plane: box => {
    const from = el("span", "a-place"); from.append(el("b", null, "🇮🇹"), el("small", null, "Italia"));
    const to = el("span", "a-place"); to.append(el("b", null, "🇵🇪"), el("small", null, "Perú"));
    const sky = el("span", "a-sky");
    sky.append(el("span", "a-plane", "✈️"), el("span", "a-arrive", "💜"));
    box.append(from, sky, to);
  },
  // un bacio con i cuoricini che volano via
  kiss: box => {
    box.append(el("span", "a-kiss", "💋"));
    ["💜", "💙", "💜"].forEach((h, i) => box.append(el("span", "a-pop a-pop" + i, h)));
  },
  // due cuori lontani (il mio e il tuo) che si avvicinano
  together: box => box.append(el("span", "a-left", "💙"), el("span", "a-right", "💜"), el("span", "a-merge", "💙💜")),
};

function renderAnim(kind) {
  const box = $("dlg-anim");
  box.replaceChildren();
  const draw = ANIMS[kind];
  box.className = "anim" + (draw ? " anim-" + kind : "");
  box.hidden = !draw;
  if (draw) draw(box);
  return Boolean(draw);
}

$("dlg-close").addEventListener("click", () => $("door-dialog").close());
$("door-dialog").addEventListener("click", e => { if (e.target === $("door-dialog")) $("door-dialog").close(); });

// =============================================================================
// Chi e' online (WebSocket)
// =============================================================================
let ws = null;
let wsRetry = 0;
let wsTimer = null;
let hideTimer = null;
// Se cambi scheda o app per poco (es. rispondi a un messaggio) resti online ancora 3 minuti.
const HIDDEN_GRACE_MS = 3 * 60 * 1000;

function wsUrl() {
  const base = API || location.origin;
  return base.replace(/^http/, "ws") + "/ws";
}

function connect() {
  if (ws || !state.me) return;
  clearTimeout(wsTimer);
  const socket = new WebSocket(wsUrl());
  ws = socket;
  socket.onopen = () => { wsRetry = 0; socket.send(JSON.stringify({ key })); };
  socket.onmessage = e => {
    let msg;
    try { msg = JSON.parse(e.data); } catch (_) { return; }
    if (msg.type === "presence") { state.presence = msg; renderPresence(); }
    if (msg.type === "meeting") onMeetingNotice(msg);
    if (msg.type === "photo") { toast(`📷 ${state.names[msg.by]} ha aggiunto una foto ai ricordi`, 4000); refreshPhotos(); }
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

// Quando lei chiude l'app o resta su un'altra scheda per piu' di 3 minuti risulta offline.
// Quando torna, ci riconnettiamo.
document.addEventListener("visibilitychange", () => {
  if (!state.me) return;
  if (document.hidden) {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      clearTimeout(wsTimer);
      if (ws) { const s = ws; ws = null; s.close(1000); }
    }, HIDDEN_GRACE_MS);
  } else {
    clearTimeout(hideTimer);
    connect();
    refreshMeetings();
    refreshAdvent();
  }
});

// Se la pagina e' rimasta aperta in background fino al giorno dopo, apriamo le caselle nuove.
// force: ridisegna anche se il giorno e' lo stesso (es. sono cambiate le foto delle caselle).
async function refreshAdvent(force = false) {
  try {
    const advent = await api("/api/advent");
    if (advent.today === state.today && !force) return;
    state.today = advent.today;
    renderAdvent(advent);
    renderMeetings(); // "passato / prossimo" dipende dalla data di oggi
  } catch (_) { /* riproveremo al prossimo ritorno sulla pagina */ }
}

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
  if (together && !state.together) {
    toast("💙 Siete online insieme 💜", 3500);
    heartBurst();
  }
  state.together = together;
  $("together").hidden = !together;
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

// "il 10 gennaio" oppure "il 10 gennaio alle 20:30"
function when(date, time) {
  return "il " + fmtShort.format(parseDay(date)) + (time ? " alle " + time : "");
}

function onMeetingNotice(msg) {
  const text = NOTICE[msg.kind](state.names[msg.by], when(msg.date, msg.time));
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
  date.textContent = fmtLong.format(parseDay(m.date)) + (m.time ? " · " + m.time : "");
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
    if (ev.date) line += ` (${fmtShort.format(parseDay(ev.date))}${ev.time ? " " + ev.time : ""})`;
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
  $("mf-time").value = editing && editing.time ? editing.time : "";
  $("mf-text").value = editing ? editing.text : "";
  updateCounter();
  $("meeting-dialog").showModal();
}

$("mf-text").addEventListener("input", updateCounter);
$("mf-cancel").addEventListener("click", () => $("meeting-dialog").close());
$("new-meeting").addEventListener("click", () => openMeetingForm(null));
$("meeting-form").addEventListener("submit", async e => {
  e.preventDefault();
  const body = { date: $("mf-date").value, time: $("mf-time").value || null, text: $("mf-text").value.trim() };
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
// Ricordi (foto)
// =============================================================================
// Le foto si scaricano con la chiave nell'header, quindi passano da fetch -> blob -> URL locale.
const photoUrls = new Map();
async function photoSrc(id) {
  if (!photoUrls.has(id)) {
    photoUrls.set(id, fetch(API + "/api/photos/" + encodeURIComponent(id), { headers: { "X-Key": key || "" } })
      .then(res => { if (!res.ok) throw new Error("foto"); return res.blob(); })
      .then(blob => URL.createObjectURL(blob))
      .catch(e => { photoUrls.delete(id); throw e; }));
  }
  return photoUrls.get(id);
}

async function refreshPhotos() {
  if (!state.me) return;
  try {
    const data = await api("/api/photos");
    const changed = data.photos.map(p => p.id).join() !== state.photos.map(p => p.id).join();
    state.photos = data.photos;
    renderPhotos();
    if (changed) refreshAdvent(true); // le caselle con foto pescano dai ricordi
  } catch (_) { /* riproveremo */ }
}

// Collage a mosaico: le foto orizzontali occupano due colonne, le verticali due righe,
// e ogni tanto una foto grande. Il resto lo incastra la griglia (grid-auto-flow: dense).
function renderPhotos() {
  const box = $("collage");
  box.replaceChildren();
  const list = [...state.photos].reverse(); // le piu' recenti in alto
  $("photo-count").textContent = list.length ? (list.length === 1 ? "1 foto" : list.length + " foto") : "";
  if (list.length === 0) {
    box.append(el("p", "empty", "Ancora nessuna foto. Aggiungi il primo ricordo 📷"));
    return;
  }
  list.forEach((p, i) => {
    const tile = document.createElement("button");
    tile.type = "button";
    tile.className = "tile";
    const img = document.createElement("img");
    img.alt = "Una nostra foto";
    img.addEventListener("load", () => {
      const ratio = img.naturalWidth / img.naturalHeight;
      if (i % 7 === 0 && list.length > 2) tile.classList.add("big");
      else if (ratio > 1.25) tile.classList.add("wide");
      else if (ratio < 0.8) tile.classList.add("tall");
      tile.classList.add("ready");
    });
    photoSrc(p.id).then(src => { img.src = src; }).catch(() => {});
    tile.append(img);
    tile.addEventListener("click", () => openPhoto(p));
    box.append(tile);
  });
}

function openPhoto(p) {
  $("pv-img").removeAttribute("src");
  photoSrc(p.id).then(src => { $("pv-img").src = src; }).catch(() => {});
  $("pv-who").textContent = "Aggiunta da " + state.names[p.by] + " · " + fmtShort.format(new Date(p.at));
  $("pv-delete").onclick = async () => {
    if (!confirm("Togliere questa foto dai ricordi?")) return;
    try {
      await apiPost("/api/photos/" + encodeURIComponent(p.id) + "/delete");
      $("photo-dialog").close();
      toast("Foto tolta");
      refreshPhotos();
    } catch (e) { toast("⚠️ " + e.message, 4000); }
  };
  $("photo-dialog").showModal();
}
$("pv-close").addEventListener("click", () => $("photo-dialog").close());
$("photo-dialog").addEventListener("click", e => { if (e.target === $("photo-dialog")) $("photo-dialog").close(); });

// Il telefono rimpicciolisce la foto (lato lungo 1600 px, JPEG): da 5 MB a qualche centinaio di KB.
const MAX_SIDE = 1600;
const MAX_UPLOAD = 2400000;
async function shrink(file) {
  const bmp = await createImageBitmap(file, { imageOrientation: "from-image" });
  const scale = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bmp.width * scale);
  canvas.height = Math.round(bmp.height * scale);
  canvas.getContext("2d").drawImage(bmp, 0, 0, canvas.width, canvas.height);
  bmp.close();
  for (const q of [0.82, 0.7, 0.55]) {
    const blob = await new Promise(r => canvas.toBlob(r, "image/jpeg", q));
    if (blob && blob.size <= MAX_UPLOAD) return blob;
  }
  throw new Error("Foto troppo grande");
}

async function uploadPhoto(blob) {
  const res = await fetch(API + "/api/photos", {
    method: "POST",
    headers: { "X-Key": key || "", "Content-Type": "image/jpeg" },
    body: blob,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Caricamento non riuscito");
  return data;
}

$("photo-input").addEventListener("change", async e => {
  const files = [...e.target.files];
  e.target.value = "";
  let ok = 0;
  for (const [i, file] of files.entries()) {
    toast(files.length > 1 ? `Carico la foto ${i + 1} di ${files.length}…` : "Carico la foto…", 60000);
    try {
      await uploadPhoto(await shrink(file));
      ok++;
    } catch (err) {
      toast("⚠️ " + err.message, 4000);
    }
  }
  if (ok) toast(ok > 1 ? `📷 ${ok} foto aggiunte` : "📷 Foto aggiunta", 3000);
  refreshPhotos();
});

// =============================================================================
// Tab
// =============================================================================
function showView(name) {
  for (const v of ["advent", "photos", "meetings"]) $("view-" + v).hidden = name !== v;
  if (name === "photos") refreshPhotos();
  for (const b of $("tabs").querySelectorAll("button")) b.classList.toggle("active", b.dataset.view === name);
  try { sessionStorage.setItem("avvento.view", name); } catch (_) {}
}
for (const b of $("tabs").querySelectorAll("button")) b.addEventListener("click", () => showView(b.dataset.view));

// =============================================================================
// Neve
// =============================================================================
// Una sola animazione; setSnow(n) cambia quanti fiocchi ci sono.
const snow = { flakes: [], hearts: [], running: false, w: 0, h: 0 };
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

let heartN = 0;
function newHeart(randomY) {
  return { ...newFlake(randomY), y: randomY ? Math.random() * snow.h : -20, emoji: heartN++ % 2 ? "💜" : "💙" };
}

function setSnow(count, hearts = 0) {
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
      // Quando siete online insieme cadono anche cuori: 💙 il mio, 💜 il tuo
      ctx.font = 14 * dpr + "px sans-serif";
      for (const h of snow.hearts) {
        h.y += h.speed * dpr;
        h.phase += 0.02;
        h.x += Math.sin(h.phase) * 0.5 * dpr;
        if (h.y > snow.h + 20) Object.assign(h, newHeart(false));
        ctx.fillText(h.emoji, h.x, h.y);
      }
      requestAnimationFrame(frame);
    })();
  }

  while (snow.flakes.length < count) snow.flakes.push(newFlake(true));
  snow.flakes.length = count;
  while (snow.hearts.length < hearts) snow.hearts.push(newHeart(false));
  snow.hearts.length = hearts;
}

// Una pioggia di cuori quando vi trovate online insieme
function heartBurst() {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const box = document.createElement("div");
  box.className = "burst";
  box.setAttribute("aria-hidden", "true");
  for (let i = 0; i < 10; i++) {
    const h = el("span", "a-float", i % 2 ? "💜" : "💙");
    h.style.setProperty("--x", (3 + Math.random() * 94).toFixed(0) + "%");
    h.style.setProperty("--d", (Math.random() * 1.2).toFixed(2) + "s");
    h.style.setProperty("--t", (2.2 + Math.random() * 1.5).toFixed(2) + "s");
    h.style.setProperty("--s", (1.2 + Math.random() * 1.4).toFixed(2) + "rem");
    box.append(h);
  }
  document.body.append(box);
  setTimeout(() => box.remove(), 5200);
}

// Neve leggera da soli, forte quando siete online insieme (con i cuori), fortissima a Natale.
function updateSnow() {
  setSnow(state.christmas ? 160 : bothOnline() ? 120 : 40, bothOnline() ? 8 : 0);
}

// =============================================================================
// Avvio
// =============================================================================
// Schermata iniziale: almeno 1,8 s, poi sfuma (se il server dorme, dopo 4 s lascia il posto al loader).
const SPLASH_MIN_MS = 1800;
const SPLASH_MAX_MS = 4000;
const splashStart = Date.now();
let splashGone = false;
async function hideSplash() {
  if (splashGone) return;
  splashGone = true;
  await sleep(Math.max(0, SPLASH_MIN_MS - (Date.now() - splashStart)));
  $("splash").classList.add("out");
  setTimeout(() => $("splash").remove(), 700);
}
setTimeout(hideSplash, SPLASH_MAX_MS);

const HEART = { u1: "💙", u2: "💜" };

async function init() {
  if (!key) {
    hideSplash();
    showError("Per entrare apri il link personale che hai ricevuto 💌");
    return;
  }
  setSnow(40);
  const stopLoader = startLoader();
  try {
    // Le tre chiamate partono insieme: meno attesa.
    const [me, advent, meetings] = await Promise.all([api("/api/me"), api("/api/advent"), api("/api/meetings")]);
    stopLoader();
    const heart = HEART[me.id] || "💜";
    $("splash-sub").textContent = "Hola, " + me.name + " " + heart;
    hideSplash();
    state.me = me;
    state.names = { [me.id]: me.name, [me.otherId]: me.otherName };
    state.today = advent.today;
    state.meetings = meetings.meetings;
    $("greeting").textContent = "Ciao " + me.name + " " + heart;
    renderAdvent(advent);
    renderMeetings();
    $("tabs").hidden = false;
    let view = "advent";
    try { view = sessionStorage.getItem("avvento.view") || view; } catch (_) {}
    showView(view);
    connect();
  } catch (e) {
    stopLoader();
    hideSplash();
    showError(e.message === "unauthorized"
      ? "Questo link non e' valido 🤔"
      : "Non riesco a collegarmi, riprova tra poco.");
  }
}

init();
