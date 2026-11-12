"use strict";

// ---- Chiave personale -------------------------------------------------------
// Il link che mandi e' tipo https://sito/?k=CHIAVE. La salviamo nel telefono
// e la togliamo dalla barra degli indirizzi (cosi' non finisce negli screenshot).
const KEY_STORAGE = "avvento.key";
const OPENED_STORAGE = "avvento.opened";

function readKey() {
  const params = new URLSearchParams(location.search);
  const fromUrl = params.get("k");
  if (fromUrl) {
    try { localStorage.setItem(KEY_STORAGE, fromUrl); } catch (_) {}
    history.replaceState(null, "", location.pathname);
    return fromUrl;
  }
  try { return localStorage.getItem(KEY_STORAGE); } catch (_) { return null; }
}

const key = readKey();
const API = (window.AVVENTO_API || "").replace(/\/$/, "");

// ---- Chiamate all'API, con "sveglia" --------------------------------------------
// Sul piano free di Render il server dorme dopo 15 minuti e ci mette ~1 minuto
// a ripartire. Intanto riproviamo con calma e mostriamo il loader.
const WAKE_TIMEOUT_MS = 120000;
const RETRY_MS = 3000;
const sleep = ms => new Promise(r => setTimeout(r, ms));

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

const loader = document.getElementById("loader");
const LOADER_MESSAGES = [
  "Sto svegliando il calendario…",
  "Le renne si stanno allacciando le scarpe 🦌",
  "Scaldo la cioccolata ☕",
  "Ancora un attimo, quasi pronto ✨",
];

function startLoader() {
  let i = 0;
  // Lo mostriamo solo se serve: se il server risponde subito, niente flash.
  const show = setTimeout(() => { loader.hidden = false; }, 700);
  const rotate = setInterval(() => {
    i = (i + 1) % LOADER_MESSAGES.length;
    document.getElementById("loader-text").textContent = LOADER_MESSAGES[i];
  }, 5000);
  return () => { clearTimeout(show); clearInterval(rotate); loader.hidden = true; };
}

// ---- Caselle gia' aperte (salvate sul telefono, per l'effetto "visto") ------
function openedSet() {
  try { return new Set(JSON.parse(localStorage.getItem(OPENED_STORAGE) || "[]")); }
  catch (_) { return new Set(); }
}
function markOpened(day) {
  const s = openedSet();
  s.add(day);
  try { localStorage.setItem(OPENED_STORAGE, JSON.stringify([...s])); } catch (_) {}
}

// ---- UI ----------------------------------------------------------------------
// Ordine "mescolato" fisso, come nei calendari veri.
// 4 caselle grandi (occupano 2 posti): 24 + 4 = 28 posti = 7 righe piene da 4.
const ORDER = [7, 15, 1, 22, 11, 4, 18, 24, 9, 2, 13, 20, 5, 16, 23, 10, 3, 19, 8, 14, 21, 6, 12, 17];
const BIG = new Set([24, 13, 1, 19]);
const CHRISTMAS = 25;
const ICONS = { text: "💌", song: "🎵", voucher: "🎟️", question: "❓", movie: "🎬", recipe: "🍪", christmas: "🎄" };

const grid = document.getElementById("grid");
const dialog = document.getElementById("door-dialog");
const toastEl = document.getElementById("toast");

function toast(msg) {
  toastEl.textContent = msg;
  toastEl.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => toastEl.classList.remove("show"), 2200);
}

function showError(msg) {
  const el = document.getElementById("error");
  el.textContent = msg;
  el.hidden = false;
}

function daysUntil(day, todayIso) {
  const today = new Date(todayIso + "T00:00:00");
  const target = new Date(today.getFullYear(), 11, day);
  return Math.round((target - today) / 86400000);
}

function render(data) {
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
  document.getElementById("dlg-day").textContent = door.day + " dicembre";
  document.getElementById("dlg-icon").textContent = (c && ICONS[c.type]) || "🎁";
  // textContent (mai innerHTML): il testo viene mostrato cosi' com'e', nessun codice iniettato
  document.getElementById("dlg-title").textContent = (c && c.title) || "Sorpresa in arrivo...";
  document.getElementById("dlg-text").textContent = (c && c.text) || "Questa casella e' ancora vuota, ci sto lavorando 😅";

  const link = document.getElementById("dlg-link");
  if (c && c.url && c.url.startsWith("https://")) {
    link.href = c.url;
    link.textContent = c.type === "song" ? "Ascolta 🎵" : "Apri 🔗";
    link.hidden = false;
  } else {
    link.hidden = true;
  }

  dialog.showModal();
  markOpened(door.day);
  btn.classList.remove("ready");
  btn.classList.add("opened");
  if (door.day === CHRISTMAS) setSnow(140); // a Natale nevica forte
}

document.getElementById("dlg-close").addEventListener("click", () => dialog.close());
dialog.addEventListener("click", e => { if (e.target === dialog) dialog.close(); });

// ---- Neve --------------------------------------------------------------------
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
  const canvas = document.getElementById("snow");

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

// ---- Avvio -------------------------------------------------------------------
async function init() {
  if (!key) {
    showError("Per entrare apri il link personale che hai ricevuto 💌");
    return;
  }
  setSnow(40); // piu' avanti: nevicata piu' forte quando siete online tutti e due
  const stopLoader = startLoader();
  try {
    // Le due chiamate partono insieme: meno attesa.
    const [me, advent] = await Promise.all([api("/api/me"), api("/api/advent")]);
    stopLoader();
    document.getElementById("greeting").textContent = "Ciao " + me.name + " ❄️";
    render(advent);
  } catch (e) {
    stopLoader();
    showError(e.message === "unauthorized"
      ? "Questo link non e' valido 🤔"
      : "Non riesco a collegarmi, riprova tra poco.");
  }
}

init();
