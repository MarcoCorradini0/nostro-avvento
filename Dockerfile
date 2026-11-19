# Immagine ottimizzata per avviarsi in fretta su poca CPU (Render free: 0.1 CPU).
# Java 25 (LTS) su Alpine 3.24: stessa versione di Alpine nelle due fasi.

# ---- Fase 1: compilazione + mini-Java su misura ------------------------------
FROM eclipse-temurin:25-jdk-alpine-3.24 AS build
WORKDIR /src

# Prima solo i file di Gradle: Docker mette in cache questo strato,
# cosi' le dipendenze non si riscaricano a ogni modifica del codice.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon -q buildFatJar -x test && mv build/libs/*-all.jar /app.jar

# jlink: un Java che contiene solo i moduli che servono davvero.
# jdeps segnalerebbe anche java.desktop (java.beans della libreria di config HOCON) e
# java.instrument (debugger delle coroutine): sono parti opzionali che non usiamo, quindi le escludiamo.
# java.sql serve al driver PostgreSQL. (La crittografia EC per TLS da Java 22 e' gia' in java.base.)
RUN jlink \
      --add-modules java.base,java.logging,java.management,java.sql,jdk.unsupported \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /jre

# ---- Fase 2: immagine finale minima ------------------------------------------
FROM alpine:3.24
# Aggiornamenti di sicurezza di Alpine usciti dopo l'immagine base.
RUN apk upgrade --no-cache
ENV JAVA_HOME=/jre PATH=/jre/bin:$PATH PORT=8080 WEB_DIR=/app/web
COPY --from=build /jre /jre
WORKDIR /app
COPY --from=build /app.jar app.jar
COPY advent.example.json .
# La pagina (15 KB) e' anche nell'immagine: cosi' funziona da sola (k8s locale, link di riserva).
COPY web ./web

# Le opzioni della JVM: uguali nell'avvio di prova qui sotto e in quello vero (serve all'AOT cache).
# -XX:TieredStopAtLevel=1  solo il compilatore veloce (C1): parte prima, basta per un'app piccola
# -XX:+UseSerialGC         GC semplice, ideale con 1 CPU e poca RAM
ENV JAVA_TOOL_OPTIONS="-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:MaxRAMPercentage=75 -Xss512k"

# AOT cache (Java 25): avviamo l'app una volta durante la build e le facciamo servire
# le richieste tipiche. Java salva in app.aot le classi gia' caricate e collegate:
# a ogni avvio vero le legge da li' invece di rifare tutto da zero.
# -XX:MaxRAM=512m: la macchina di build di Render ha tanta RAM; senza questo Java
# disattiverebbe i "compressed oops" e la cache non sarebbe usabile con i 512 MB veri.
RUN ADVENT_FILE=advent.example.json java -XX:AOTCacheOutput=app.aot -XX:MaxRAM=512m -jar app.jar & pid=$!; \
    until wget -q -O /dev/null --header "X-Key: dev-chiave-1" http://127.0.0.1:8080/api/advent; do sleep 0.5; done; \
    wget -q -O /dev/null --header "X-Key: dev-chiave-1" http://127.0.0.1:8080/api/me; \
    wget -q -O /dev/null --header "X-Key: dev-chiave-1" http://127.0.0.1:8080/api/meetings; \
    wget -q -O /dev/null http://127.0.0.1:8080/healthz; \
    kill -TERM $pid; wait $pid; test -s app.aot

# Non giriamo come root. UID numerico: Kubernetes (runAsNonRoot) puo' verificarlo.
RUN adduser -D -H -u 10001 app
USER 10001
EXPOSE 8080

ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "app.jar"]
