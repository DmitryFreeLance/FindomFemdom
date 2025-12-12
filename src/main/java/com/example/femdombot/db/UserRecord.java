package com.example.femdombot.db;

import com.example.femdombot.model.UserState;

public class UserRecord {
    public long chatId;
    public UserState state = UserState.NEW;
    public boolean verified = false;

    public int attemptsLeft = 5;
    public Long verificationStartedAt;
    public String channelLink;
    public String verificationVideoFileId;
    public String pendingPostType;
    public Long lastStartAt;
    public Long lastCallbackAt;

    // 👇 НОВОЕ (для ручной оплаты)
    public boolean paymentApproved = false;     // админ подтвердил оплату
    public Long paymentClaimedAt;               // когда пользователь нажал "Я оплатила" (epoch millis)

    public UserRecord(long chatId) {
        this.chatId = chatId;
    }
}