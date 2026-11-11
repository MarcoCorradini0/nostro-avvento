# ---- Fase 1: compilazione -----------------------------------------------------
# Usiamo un'immagine con il JDK completo solo per compilare.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Prima solo i file di Gradle: Docker mette in cache questo strato,
# cosi' le dipendenze non si riscaricano a ogni modifica del codice.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon buildFatJar -x test

# ---- Fase 2: esecuzione -------------------------------------------------------
# Immagine finale piccola: solo il JRE, niente compilatore ne' sorgenti.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non giriamo come root: se qualcuno bucasse l'app, avrebbe pochi permessi.
# UID numerico: Kubernetes (runAsNonRoot) puo' verificare che non sia root solo con un numero.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
COPY --from=build /src/build/libs/*-all.jar /app/app.jar
COPY advent.example.json /app/advent.example.json
USER 10001

ENV PORT=8080
EXPOSE 8080
# Usa al massimo il 75% della RAM che Kubernetes assegna al container.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
