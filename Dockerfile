# ===== СБОРКА =====
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

RUN mvn -B -DskipTests package

# ===== РАНТАЙМ =====
FROM eclipse-temurin:21-jre

WORKDIR /app

# Копируем ЛЮБОЙ jar из target и называем его app.jar
# (обычно там один основной jar)
COPY --from=build /app/target/*.jar /app/app.jar

RUN mkdir -p /data

ENV DB_FILE=/data/bot.db
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]