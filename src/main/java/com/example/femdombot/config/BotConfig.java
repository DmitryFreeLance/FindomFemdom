package com.example.femdombot.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class BotConfig {
    private final String token;
    private final String username;
    private final String dbUrl;
    private final Set<Long> admins;
    private final long callbackCooldownMs;
    private final long startCooldownMs;
    private final String paymentProviderToken;

    // 👇 Куда публиковать посты (по умолчанию встроено)
    private final long publishChatId;

    public BotConfig() {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .load();

        // ОБЯЗАТЕЛЬНЫЕ
        this.token = getenvOrDotenv(dotenv, "BOT_TOKEN", true);
        this.username = getenvOrDotenv(dotenv, "BOT_USERNAME", true);
        this.paymentProviderToken = getenvOrDotenv(dotenv, "PAYMENT_PROVIDER_TOKEN", true);

        // 👇 ID чата/канала для публикаций
        // Если не задано — используем встроенное значение
        String publishChatStr = getenvOrDotenv(dotenv, "PUBLISH_CHAT_ID", false);
        this.publishChatId = (publishChatStr != null && !publishChatStr.isBlank())
                ? Long.parseLong(publishChatStr)
                : -1003256610748L;

        // БД
        String dbFile = getenvOrDotenv(dotenv, "DB_FILE", false);
        if (dbFile == null || dbFile.isBlank()) {
            dbFile = "./bot.db";
        }
        this.dbUrl = "jdbc:sqlite:" + dbFile;

        // Админы
        String adminsStr = getenvOrDotenv(dotenv, "BOT_ADMINS", false);
        if (adminsStr == null || adminsStr.isBlank()) {
            this.admins = Set.of();
        } else {
            this.admins = Arrays.stream(adminsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .collect(Collectors.toSet());
        }

        // Тайминги
        String cbCooldown = getenvOrDotenv(dotenv, "CALLBACK_COOLDOWN_MS", false);
        String startCooldown = getenvOrDotenv(dotenv, "START_COOLDOWN_MS", false);

        this.callbackCooldownMs = cbCooldown != null && !cbCooldown.isBlank()
                ? Long.parseLong(cbCooldown)
                : 300L;

        this.startCooldownMs = startCooldown != null && !startCooldown.isBlank()
                ? Long.parseLong(startCooldown)
                : 2000L;
    }

    private String getenvOrDotenv(Dotenv dotenv, String key, boolean required) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            v = dotenv.get(key);
        }
        if (required && (v == null || v.isBlank())) {
            throw new IllegalStateException("Missing required config: " + key);
        }
        return v;
    }

    public String getToken() {
        return token;
    }

    public String getUsername() {
        return username;
    }

    public String getDbUrl() {
        return dbUrl;
    }

    public Set<Long> getAdmins() {
        return admins;
    }

    public long getCallbackCooldownMs() {
        return callbackCooldownMs;
    }

    public long getStartCooldownMs() {
        return startCooldownMs;
    }

    public String getPaymentProviderToken() {
        return paymentProviderToken;
    }

    public long getPublishChatId() {
        return publishChatId;
    }
}