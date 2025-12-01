package com.example.femdombot.db;

import com.example.femdombot.model.PostType;
import com.example.femdombot.model.UserState;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class Db {
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private final String url;

    public Db(String url) {
        this.url = url;
        init();
    }

    private void init() {
        try (Connection c = getConnection();
             Statement st = c.createStatement()) {

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                      chat_id                    INTEGER PRIMARY KEY,
                      state                      TEXT,
                      verified                   INTEGER DEFAULT 0,
                      attempts_left              INTEGER DEFAULT 5,
                      verification_started_at    INTEGER,
                      channel_link               TEXT,
                      verification_video_file_id TEXT,
                      pending_post_type          TEXT,
                      last_start_at              INTEGER,
                      last_callback_at           INTEGER
                    )
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS posts (
                      id             INTEGER PRIMARY KEY AUTOINCREMENT,
                      chat_id        INTEGER,
                      type           TEXT,
                      media_file_id  TEXT,
                      caption        TEXT,
                      status         TEXT,
                      queue_position INTEGER,
                      scheduled_at   INTEGER
                    )
                    """);
        } catch (SQLException e) {
            throw new RuntimeException("DB init error", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    // ---------- USERS ----------

    public UserRecord findOrCreateUser(long chatId) {
        try (Connection c = getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT chat_id, state, verified, attempts_left,
                           verification_started_at, channel_link,
                           verification_video_file_id, pending_post_type,
                           last_start_at, last_callback_at
                    FROM users WHERE chat_id = ?
                    """)) {
                ps.setLong(1, chatId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        UserRecord u = new UserRecord(chatId);
                        String stateStr = rs.getString("state");
                        if (stateStr != null) {
                            u.state = UserState.valueOf(stateStr);
                        }
                        u.verified = rs.getInt("verified") == 1;
                        u.attemptsLeft = rs.getInt("attempts_left");
                        long vs = rs.getLong("verification_started_at");
                        u.verificationStartedAt = rs.wasNull() ? null : vs;
                        u.channelLink = rs.getString("channel_link");
                        u.verificationVideoFileId = rs.getString("verification_video_file_id");
                        u.pendingPostType = rs.getString("pending_post_type");
                        long ls = rs.getLong("last_start_at");
                        u.lastStartAt = rs.wasNull() ? null : ls;
                        long lc = rs.getLong("last_callback_at");
                        u.lastCallbackAt = rs.wasNull() ? null : lc;
                        return u;
                    }
                }
            }

            UserRecord u = new UserRecord(chatId);
            saveUser(u);
            return u;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveUser(UserRecord u) {
        try (Connection c = getConnection()) {
            String update = """
                    UPDATE users SET
                      state = ?, verified = ?, attempts_left = ?,
                      verification_started_at = ?, channel_link = ?,
                      verification_video_file_id = ?, pending_post_type = ?,
                      last_start_at = ?, last_callback_at = ?
                    WHERE chat_id = ?
                    """;
            int updated;
            try (PreparedStatement ps = c.prepareStatement(update)) {
                ps.setString(1, u.state != null ? u.state.name() : null);
                ps.setInt(2, u.verified ? 1 : 0);
                ps.setInt(3, u.attemptsLeft);
                if (u.verificationStartedAt == null) ps.setNull(4, Types.BIGINT);
                else ps.setLong(4, u.verificationStartedAt);
                ps.setString(5, u.channelLink);
                ps.setString(6, u.verificationVideoFileId);
                ps.setString(7, u.pendingPostType);
                if (u.lastStartAt == null) ps.setNull(8, Types.BIGINT);
                else ps.setLong(8, u.lastStartAt);
                if (u.lastCallbackAt == null) ps.setNull(9, Types.BIGINT);
                else ps.setLong(9, u.lastCallbackAt);
                ps.setLong(10, u.chatId);
                updated = ps.executeUpdate();
            }

            if (updated == 0) {
                String insert = """
                        INSERT INTO users
                        (chat_id, state, verified, attempts_left,
                         verification_started_at, channel_link,
                         verification_video_file_id, pending_post_type,
                         last_start_at, last_callback_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement ps = c.prepareStatement(insert)) {
                    ps.setLong(1, u.chatId);
                    ps.setString(2, u.state != null ? u.state.name() : null);
                    ps.setInt(3, u.verified ? 1 : 0);
                    ps.setInt(4, u.attemptsLeft);
                    if (u.verificationStartedAt == null) ps.setNull(5, Types.BIGINT);
                    else ps.setLong(5, u.verificationStartedAt);
                    ps.setString(6, u.channelLink);
                    ps.setString(7, u.verificationVideoFileId);
                    ps.setString(8, u.pendingPostType);
                    if (u.lastStartAt == null) ps.setNull(9, Types.BIGINT);
                    else ps.setLong(9, u.lastStartAt);
                    if (u.lastCallbackAt == null) ps.setNull(10, Types.BIGINT);
                    else ps.setLong(10, u.lastCallbackAt);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- POSTS ----------

    public int countUserQueuedPosts(long chatId) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COUNT(*)
                     FROM posts
                     WHERE chat_id = ? AND status IN ('QUEUED','SCHEDULED')
                     """)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int nextQueuePosition() {
        try (Connection c = getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT COALESCE(MAX(queue_position), 0) + 1
                     FROM posts
                     WHERE status IN ('QUEUED','SCHEDULED')
                     """)) {
            if (rs.next()) return rs.getInt(1);
            return 1;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void savePost(PostRecord p) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO posts
                       (chat_id, type, media_file_id, caption,
                        status, queue_position, scheduled_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setLong(1, p.chatId);
            ps.setString(2, p.type.name());
            ps.setString(3, p.mediaFileId);
            ps.setString(4, p.caption);
            ps.setString(5, p.status);
            ps.setInt(6, p.queuePosition);

            long epoch = p.scheduledAt
                    .atZone(MOSCOW_ZONE)   // фиксируем МСК
                    .toEpochSecond();
            ps.setLong(7, epoch);

            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countQueuedPostsByType(PostType type) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COUNT(*)
                     FROM posts
                     WHERE type = ? AND status IN ('QUEUED','SCHEDULED')
                     """)) {
            ps.setString(1, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


}