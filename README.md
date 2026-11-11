# 🎄 Il nostro Avvento

[![CI](https://github.com/MarcoCorradini0/nostro-avvento/actions/workflows/ci.yml/badge.svg)](https://github.com/MarcoCorradini0/nostro-avvento/actions/workflows/ci.yml)

Una piccola web app mobile-first per due persone lontane: un calendario dell'avvento
con una sorpresa al giorno, e presto stato online, nevicata condivisa e un calendario
per organizzarsi quando vedersi.

Progetto personale per imparare **Kotlin** (backend Ktor) e **Kubernetes**.

## Stack

- **Backend:** Kotlin 2.4 + Ktor 3 (Netty), kotlinx.serialization
- **Frontend:** HTML/CSS/JS senza framework, mobile-first
- **Build:** Gradle 9 (Kotlin DSL)
- **Deploy (in arrivo):** Docker → Kubernetes (k3d in locale, k3s online)

## Avvio in locale

Requisiti: JDK 21+ (va bene anche quello incluso in IntelliJ).

```bash
./gradlew run
```

Poi apri <http://localhost:8080/?k=dev-chiave-1> (oppure `dev-chiave-2` per l'altra persona).

Per provare come se fosse già dicembre:

```bash
FAKE_TODAY=2026-12-05 ./gradlew run
```

Test:

```bash
./gradlew test
```

## Su Kubernetes in locale (k3d)

Requisiti: Docker Desktop, `kubectl`, [k3d](https://k3d.io) (`winget install k3d.k3d`).

```bash
# 1. Secret locali (una volta sola): vedi deploy/k8s/secrets/README.md
cp deploy/k8s/secrets/keys.env.example deploy/k8s/secrets/keys.env   # poi cambia le chiavi!
cp advent.example.json deploy/k8s/secrets/advent.json

# 2. Cluster (una volta sola). Porta 8090: la 8081 è spesso occupata (es. antivirus aziendali)
k3d cluster create avvento -p "8090:80@loadbalancer" --api-port 127.0.0.1:6550 --wait

# 3. Build dell'immagine e import nel cluster (a ogni nuova versione)
docker build -t avvento:0.1.1 .
k3d image import avvento:0.1.1 -c avvento

# 4. Deploy
kubectl apply -k deploy/k8s
kubectl -n avvento rollout status deployment/avvento
```

Poi apri `http://avvento.localhost:8090/?k=<USER1_KEY o USER2_KEY>`.

Comandi utili:

```bash
kubectl -n avvento get pods               # i pod (2 repliche)
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
| `ADVENT_FILE` | `advent.example.json` | File con il contenuto delle 24 caselle |
| `ADVENT_YEAR` | anno corrente | Anno del calendario |
| `TIMEZONE` | `Europe/Rome` | Fuso orario per lo sblocco |
| `FAKE_TODAY` | – | Solo per test: finge che oggi sia questa data |

## Contenuti dell'avvento

Copia `advent.example.json` in `advent.json` (che è in `.gitignore`, così le sorprese
non finiscono su GitHub) e avvia con `ADVENT_FILE=advent.json`.

Tipi di casella: `text` 💌, `song` 🎵, `voucher` 🎟️, `question` ❓, `movie` 🎬, `recipe` 🍪.
Il campo `url` (facoltativo) deve iniziare con `https://`.

## Sicurezza e privacy

- Nessuna registrazione, nessuna email: ogni persona ha un link personale con una chiave segreta.
- Le chiavi sono confrontate a tempo costante e non sono mai nel codice in produzione.
- Il server invia il contenuto di una casella **solo** dopo la data di sblocco (ora del server).
- Il frontend usa sempre `textContent`, mai `innerHTML`: niente HTML iniettato.

## Roadmap

- [x] Calendario dell'avvento
- [x] Dockerfile + deploy su Kubernetes (k3d)
- [ ] Online su k3s con HTTPS
- [ ] Stato online 🟢/🔴 via WebSocket + nevicata quando ci siamo tutti e due
- [ ] Calendario incontri con richiesta / accetta / rifiuta / modifica
