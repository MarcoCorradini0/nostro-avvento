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

async function api(path) {
  const res = await fetch(path, { headers: { "X-Key": key || "" } });
  if (res.status === 401) throw new Error("unauthorized");
  if (!res.ok) throw new Error("http " + res.status);
  return res.json();
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
const ICONS = { text: "💌", song: "🎵", voucher: "🎟️", question: "❓", movie: "🎬", recipe: "🍪" };

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
}

document.getElementById("dlg-close").addEventListener("click", () => dialog.close());
dialog.addEventListener("click", e => { if (e.target === dialog) dialog.close(); });

// ---- Neve leggera ------------------------------------------------------------
function startSnow(count) {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const canvas = document.getElementById("snow");
  const ctx = canvas.getContext("2d");
  const dpr = window.devicePixelRatio || 1;
  let w = 0, h = 0;

  function resize() {
    w = canvas.width = innerWidth * dpr;
    h = canvas.height = innerHeight * dpr;
    canvas.style.width = innerWidth + "px";
    canvas.style.height = innerHeight + "px";
  }
  resize();
  addEventListener("resize", resize);

  const flakes = Array.from({ length: count }, () => ({
    x: Math.random() * w,
    y: Math.random() * h,
    r: (Math.random() * 2 + 1) * dpr,
    speed: Math.random() * 0.6 + 0.3,
    phase: Math.random() * Math.PI * 2,
  }));

  (function frame() {
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = "rgba(255,255,255,.8)";
    for (const f of flakes) {
      f.y += f.speed * dpr;
      f.phase += 0.01;
      f.x += Math.sin(f.phase) * 0.4 * dpr;
      if (f.y > h) { f.y = -5; f.x = Math.random() * w; }
      ctx.beginPath();
      ctx.arc(f.x, f.y, f.r, 0, Math.PI * 2);
      ctx.fill();
    }
    requestAnimationFrame(frame);
  })();
}

// ---- Avvio -------------------------------------------------------------------
async function init() {
  if (!key) {
    showError("Per entrare apri il link personale che hai ricevuto 💌");
    return;
  }
  try {
    const me = await api("/api/me");
    document.getElementById("greeting").textContent = "Ciao " + me.name + " ❄️";
    render(await api("/api/advent"));
    startSnow(40); // piu' avanti: nevicata piu' forte quando siete online tutti e due
  } catch (e) {
    showError(e.message === "unauthorized"
      ? "Questo link non e' valido 🤔"
      : "Non riesco a collegarmi, riprova tra poco.");
  }
}

init();
