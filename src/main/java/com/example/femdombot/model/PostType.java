package com.example.femdombot.model;

public enum PostType {
    STANDARD("✨ Стандартный пост 400₽"),
    STORY("📸 История 500₽"),
    VIP("👑 VIP-пост 600₽"),
    INSTANT("⚡ Моментальный пост 900₽"),
    INSTANT_PIN("📌 Моментальный пост + закреп 1100₽"),
    INSTANT_STORY("🚀 Двойной эффект 1300₽");

    private final String title;

    PostType(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}