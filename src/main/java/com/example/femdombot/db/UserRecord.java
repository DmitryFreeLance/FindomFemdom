package com.example.femdombot.db;

import com.example.femdombot.model.UserState;

public class UserRecord {
    public long chatId;
    public UserState state = UserState.NEW;
    public boolean verified = false;

    public int attemptsLeft = 5;
    public Long verificationStartedAt;         // epoch millis
    public String channelLink;
    public String verificationVideoFileId;
    public String pendingPostType;            // название enum PostType
    public Long lastStartAt;                  // защита от двойного /start
    public Long lastCallbackAt;               // можно хранить и тут (на будущее)

    public UserRecord(long chatId) {
        this.chatId = chatId;
    }
}