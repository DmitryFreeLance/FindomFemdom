package com.example.femdombot.db;

import com.example.femdombot.model.PostType;

import java.time.LocalDateTime;

public class PostRecord {
    public long id;
    public long chatId;
    public PostType type;

    // PHOTO / VIDEO (для корректной отправки)
    public String mediaType;

    public String mediaFileId;
    public String caption;

    public int queuePosition;
    public LocalDateTime scheduledAt;

    // PENDING_PAYMENT / QUEUED / INSTANT / PUBLISHING / PUBLISHED / FAILED / REJECTED ...
    public String status;
}