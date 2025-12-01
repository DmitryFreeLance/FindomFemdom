# ===== СБОРКА =====
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Сначала pom.xml (для кеша зависимостей)
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Теперь исходники
COPY src ./src

# Сборка jar
RUN mvn -B -DskipTests package

# ===== РАНТАЙМ =====
FROM eclipse-temurin:21-jre

WORKDIR /app

# Копируем собранный jar (подхватит твой femdombot-*.jar)
COPY --from=build /app/target/*-SNAPSHOT.jar app.jar

# Папка для базы
RUN mkdir -p /data

# Переменная по умолчанию — можно переопределить через docker run
ENV DB_FILE=/data/bot.db
ENV JAVA_OPTS=""

# Запуск
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]