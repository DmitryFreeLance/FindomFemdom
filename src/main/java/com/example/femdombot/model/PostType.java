package com.example.femdombot.model;

public enum PostType {
    STANDARD("✨ Стандартный пост 400₽"),
    STORY("📸 История 500₽"),
    VIP("👑 VIP-пост 600₽"),
    INSTANT("⚡ Моментальный пост 900₽"),
    INSTANT_PIN("📌 Моментальный пост + закреп 1100₽"),
    INSTANT_STORY("🚀 Двойной эффект 1300₽"),

    // ✅ НОВОЕ: кастомная публикация по выбранным дате и времени (цена зависит от слота)
    SCHEDULED_TIME("🗓️⏰ Выбрать дату и время");

    private final String title;

    PostType(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}