package com.example.femdombot.db;

import com.example.femdombot.model.PostType;

import java.time.LocalDateTime;

public class PostRecord {
    public long id;
    public long chatId;
    public PostType type;
    public String mediaFileId;
    public String caption;
    public int queuePosition;
    public LocalDateTime scheduledAt;
    public String status; // QUEUED / PUBLISHED etc.
}