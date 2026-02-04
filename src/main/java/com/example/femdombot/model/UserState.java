package com.example.femdombot.model;

public enum UserState {
    NEW,
    WAIT_ROLE,
    WAIT_TERMS_DECISION,
    WAIT_VERIFICATION_VIDEO,
    WAIT_CHANNEL_LINK,
    WAIT_REPOST_CONFIRM,
    WAIT_ADMIN_APPROVAL,
    VERIFIED,
    BANNED,

    // постинг
    WAIT_POST_CONTENT,

    // оплата
    WAIT_PAYMENT,          // ждём перевод и кнопку "Я оплатила"
    WAIT_PAYMENT_REVIEW,   // контент получен, ждём проверки админом

    // ✅ НОВОЕ: выбор даты/времени
    WAIT_SCHEDULE_DATE,
    WAIT_SCHEDULE_TIME
}