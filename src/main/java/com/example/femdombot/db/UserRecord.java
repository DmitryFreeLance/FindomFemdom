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

    // ручная оплата
    public boolean paymentApproved = false;     // админ подтвердил оплату
    public Long paymentClaimedAt;               // когда пользователь нажал "Я оплатила" (epoch millis)

    // ✅ НОВОЕ: выбранная дата/время и сумма (для "Выбрать дату и время")
    // хранится как epoch seconds (MSK) для стабильности между рестартами
    public Long pendingScheduledAtEpochSec;     // выбранный слот публикации
    public Integer pendingAmountRub;            // цена выбранного слота

    public UserRecord(long chatId) {
        this.chatId = chatId;
    }
}