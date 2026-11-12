# Immagine ottimizzata per avviarsi in fretta su poca CPU (Render free: 0.1 CPU).

# ---- Fase 1: compilazione + mini-Java su misura ------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
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
# java.sql serve al driver PostgreSQL, jdk.crypto.ec alla connessione cifrata (TLS) verso Neon.
RUN jlink \
      --add-modules java.base,java.logging,java.management,java.sql,jdk.crypto.ec,jdk.unsupported \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --generate-cds-archive \
      --output /jre

# ---- Fase 2: immagine finale minima ------------------------------------------
FROM alpine:3.22
ENV JAVA_HOME=/jre PATH=/jre/bin:$PATH PORT=8080 WEB_DIR=/app/web
COPY --from=build /jre /jre
WORKDIR /app
COPY --from=build /app.jar app.jar
COPY advent.example.json .
# La pagina (15 KB) e' anche nell'immagine: cosi' funziona da sola (k8s locale, link di riserva).
COPY web ./web

# CDS: avviamo l'app una volta durante la build, le facciamo servire una richiesta
# e salviamo le classi gia' caricate in app.jsa. A ogni avvio vero Java le legge
# da li' invece di caricarle da zero: l'avvio e' molto piu' rapido.
RUN ADVENT_FILE=advent.example.json java -XX:ArchiveClassesAtExit=app.jsa -jar app.jar & pid=$!; \
    until wget -q -O /dev/null --header "X-Key: dev-chiave-1" http://127.0.0.1:8080/api/advent; do sleep 0.5; done; \
    kill -TERM $pid; wait $pid; test -s app.jsa

# Non giriamo come root. UID numerico: Kubernetes (runAsNonRoot) puo' verificarlo.
RUN adduser -D -H -u 10001 app
USER 10001
EXPOSE 8080

# -XX:TieredStopAtLevel=1  solo il compilatore veloce (C1): parte prima, basta per un'app piccola
# -XX:+UseSerialGC         GC semplice, ideale con 1 CPU e poca RAM
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=75", "-Xss512k", "-jar", "app.jar"]
