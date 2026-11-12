# 🎄 Il nostro Avvento

[![CI](https://github.com/MarcoCorradini0/nostro-avvento/actions/workflows/ci.yml/badge.svg)](https://github.com/MarcoCorradini0/nostro-avvento/actions/workflows/ci.yml)

Una piccola web app mobile-first per due persone lontane:

- 🎄 **Calendario dell'avvento** con una sorpresa al giorno (e una casella segreta per Natale)
- 🟢 **Chi è online** in tempo reale, con "vista 5 min fa"
- ❄️ **Nevicata insieme**: quando siete online tutti e due, nevica più forte
- 📅 **Incontri**: proponi un giorno con una descrizione (max 200 parole); l'altra persona
  accetta, rifiuta o propone una modifica, e riceve l'avviso in tempo reale

Progetto personale per imparare **Kotlin** (backend Ktor) e **Kubernetes**.

## Architettura (tutto gratis)

```
Telefono ──► GitHub Pages (pagina web/, CDN, sempre accesa)
                 │  fetch + header X-Key (CORS solo da Pages)
                 │  WebSocket /ws (presenza + avvisi)
                 ▼
             Render Free (API Ktor in Docker, si addormenta dopo 15 min)
                 │  JDBC + TLS
                 ▼
             Neon Free (PostgreSQL per gli incontri, non scade)
```

- La pagina si apre subito da GitHub Pages. Se l'API sta dormendo, mostra un loader
  e riprova finché il server non si sveglia.
- In locale la stessa immagine gira anche su **Kubernetes** (k3d).

## Stack

- **Backend:** Kotlin 2.4 + Ktor 3 (motore CIO), kotlinx.serialization, slf4j-simple
- **Frontend:** HTML/CSS/JS senza framework né build, mobile-first
- **Build:** Gradle 9 (Kotlin DSL, configuration cache)
- **Immagine:** Java ridotto con jlink + Class Data Sharing (CDS)
- **CI/CD:** GitHub Actions (test, Pages) → Render (deploy solo se i test passano)

### Avvio veloce

Misurato con i limiti di Render Free (0.1 CPU, 512 MB):

| | prima | dopo |
|---|---|---|
| Avvio della JVM fino a `/healthz` | ~23 s | **~6 s** |
| RAM | 61 MB | **40 MB** |
| Immagine | 318 MB | **164 MB** |

Cosa fa la differenza: CIO al posto di Netty, logger minimale, jlink con solo 4 moduli,
archivio CDS generato durante la build, `-XX:TieredStopAtLevel=1` e `-XX:+UseSerialGC`.

## Avvio in locale

Requisiti: JDK 21+ (va bene anche quello incluso in IntelliJ).

```bash
./gradlew run
```

Poi apri <http://localhost:8080/?k=dev-chiave-1> (oppure `dev-chiave-2` per l'altra persona).

Per provare come se fosse un altro giorno (es. Natale, per vedere la casella 25):

```bash
FAKE_TODAY=2026-12-25 ./gradlew run
```

Test:

```bash
./gradlew test
```

## Online (Render + GitHub Pages)

1. **Render:** New → Blueprint → scegli questo repo. Render legge [`render.yaml`](render.yaml)
   e chiede i valori segreti (`USER1_NAME`, `USER1_KEY`, `USER2_NAME`, `USER2_KEY`).
   Poi, nel servizio, *Environment → Secret Files* → aggiungi `advent.json`.
   Per salvare gli incontri: crea un progetto gratuito su [Neon](https://neon.com) (regione
   Frankfurt), copia la *connection string* e aggiungila su Render come `DATABASE_URL`.
2. **GitHub:** *Settings → Pages → Source: GitHub Actions*, poi in
   *Settings → Secrets and variables → Actions → Variables* crea `API_BASE` con
   l'indirizzo del servizio Render (es. `https://nostro-avvento.onrender.com`).
3. I link da mandare: `https://marcocorradini0.github.io/nostro-avvento/?k=<chiave>`.

Da lì in poi basta `git push`: i test girano, Render aggiorna l'API se sono verdi,
e Pages aggiorna la pagina solo se è cambiato qualcosa in `web/`.

## Su Kubernetes in locale (k3d)

Requisiti: Docker Desktop, `kubectl`, [k3d](https://k3d.io) (`winget install k3d.k3d`).

```bash
# 1. Secret locali (una volta sola): vedi deploy/k8s/secrets/README.md
cp deploy/k8s/secrets/keys.env.example deploy/k8s/secrets/keys.env   # poi cambia le chiavi!
cp advent.example.json deploy/k8s/secrets/advent.json

# 2. Cluster (una volta sola). Porta 8090: la 8081 è spesso occupata (es. antivirus aziendali)
k3d cluster create avvento -p "8090:80@loadbalancer" --api-port 127.0.0.1:6550 --wait

# 3. Build dell'immagine e import nel cluster (a ogni nuova versione)
docker build -t avvento:0.3.0 .
k3d image import avvento:0.3.0 -c avvento

# 4. Deploy
kubectl apply -k deploy/k8s
kubectl -n avvento rollout status deployment/avvento
```

Poi apri `http://avvento.localhost:8090/?k=<USER1_KEY o USER2_KEY>`.

Comandi utili:

```bash
kubectl -n avvento get pods               # il pod dell'app
kubectl -n avvento logs -l component=server -f --prefix   # log di tutti i pod
kubectl -n avvento delete pod -l component=server         # "uccidi" i pod: Kubernetes li ricrea
k3d cluster stop avvento                  # spegni il cluster (start per riaccenderlo)
```

Per una nuova versione: aumenta `version` in `build.gradle.kts` e `newTag` in
`deploy/k8s/kustomization.yaml`, poi ripeti i passi 3 e 4.

## Configurazione (variabili d'ambiente)

| Variabile | Default | Cosa fa |
|---|---|---|
| `PORT` | `8080` | Porta HTTP |
| `USER1_NAME` / `USER2_NAME` | `Io` / `Lei` | Nomi mostrati |
| `USER1_KEY` / `USER2_KEY` | `dev-chiave-1/2` | Chiavi segrete dei link personali. **In produzione cambiale!** |
| `ADVENT_FILE` | `advent.example.json` | File con il contenuto delle caselle |
| `ADVENT_YEAR` | anno corrente | Anno del calendario |
| `TIMEZONE` | `Europe/Rome` | Fuso orario per lo sblocco |
| `ALLOWED_ORIGINS` | – | Siti che possono chiamare l'API (CORS), separati da virgola |
| `WEB_DIR` | `web` | Cartella della pagina, servita solo se esiste |
| `DATABASE_URL` | – | PostgreSQL per gli incontri (`postgresql://utente:password@host/db?sslmode=require`). Senza: in memoria |
| `FAKE_TODAY` | – | Solo per test: finge che oggi sia questa data |

## Contenuti dell'avvento

Copia `advent.example.json` in `advent.json` (che è in `.gitignore`, così le sorprese
non finiscono su GitHub) e avvia con `ADVENT_FILE=advent.json`.

- Caselle **1–24**: si vedono sempre, si aprono dal giorno N dicembre.
- Casella **25**: la sorpresa di Natale. Il server non la manda proprio fino al 25,
  poi compare in alto, grande quanto 12 caselle, e fa nevicare forte.

Tipi di casella: `text` 💌, `song` 🎵, `voucher` 🎟️, `question` ❓, `movie` 🎬, `recipe` 🍪, `christmas` 🎄.
Il campo `url` (facoltativo) deve iniziare con `https://`.

## Sicurezza e privacy

- Nessuna registrazione, nessuna email: ogni persona ha un link personale con una chiave segreta.
- Le chiavi sono confrontate a tempo costante e non sono mai nel codice in produzione.
- Il server invia il contenuto di una casella **solo** dopo la data di sblocco (ora del server).
- CORS: l'API accetta chiamate dal browser solo dal sito configurato; il WebSocket controlla
  l'origine e richiede la chiave come primo messaggio (mai nell'URL).
- Incontri: solo chi deve rispondere può accettare o rifiutare; date passate e testi oltre
  200 parole sono rifiutati dal server, non solo dalla pagina.
- Il frontend usa sempre `textContent`, mai `innerHTML`: niente HTML iniettato.
- Container non-root, filesystem in sola lettura su Kubernetes.

## Roadmap

- [x] Calendario dell'avvento + casella segreta di Natale
- [x] Dockerfile ottimizzato + deploy su Kubernetes (k3d)
- [x] Online su Render + GitHub Pages
- [x] Stato online 🟢/🔴 via WebSocket + nevicata quando ci siamo tutti e due
- [x] Calendario incontri con richiesta / accetta / rifiuta / modifica (PostgreSQL su Neon)
- [ ] Kubernetes con più repliche: Postgres condiviso + Redis pub/sub per la presenza
