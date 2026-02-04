package com.example.femdombot.db;

import com.example.femdombot.model.PostType;
import com.example.femdombot.model.UserState;

import java.sql.*;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

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
                      media_type     TEXT,
                      media_file_id  TEXT,
                      caption        TEXT,
                      status         TEXT,
                      queue_position INTEGER,
                      scheduled_at   INTEGER,
                      published_message_id INTEGER,
                      published_at   INTEGER,
                      last_error     TEXT
                    )
                    """);

            // --- Миграции users ---
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN payment_approved INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN payment_claimed_at INTEGER"); } catch (SQLException ignored) {}

            // ✅ НОВОЕ: выбор даты/времени
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN pending_scheduled_at INTEGER"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN pending_amount_rub INTEGER"); } catch (SQLException ignored) {}

            // --- Миграции posts ---
            try { st.executeUpdate("ALTER TABLE posts ADD COLUMN media_type TEXT"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE posts ADD COLUMN published_message_id INTEGER"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE posts ADD COLUMN published_at INTEGER"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE posts ADD COLUMN last_error TEXT"); } catch (SQLException ignored) {}

            // ✅ НОВОЕ: сумма в рублях
            try { st.executeUpdate("ALTER TABLE posts ADD COLUMN amount_rub INTEGER"); } catch (SQLException ignored) {}

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
                           last_start_at, last_callback_at,
                           payment_approved, payment_claimed_at,
                           pending_scheduled_at, pending_amount_rub
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

                        u.paymentApproved = rs.getInt("payment_approved") == 1;
                        long pca = rs.getLong("payment_claimed_at");
                        u.paymentClaimedAt = rs.wasNull() ? null : pca;

                        long psa = rs.getLong("pending_scheduled_at");
                        u.pendingScheduledAtEpochSec = rs.wasNull() ? null : psa;

                        int amt = rs.getInt("pending_amount_rub");
                        u.pendingAmountRub = rs.wasNull() ? null : amt;

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
                      last_start_at = ?, last_callback_at = ?,
                      payment_approved = ?, payment_claimed_at = ?,
                      pending_scheduled_at = ?, pending_amount_rub = ?
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

                ps.setInt(10, u.paymentApproved ? 1 : 0);

                if (u.paymentClaimedAt == null) ps.setNull(11, Types.BIGINT);
                else ps.setLong(11, u.paymentClaimedAt);

                if (u.pendingScheduledAtEpochSec == null) ps.setNull(12, Types.BIGINT);
                else ps.setLong(12, u.pendingScheduledAtEpochSec);

                if (u.pendingAmountRub == null) ps.setNull(13, Types.INTEGER);
                else ps.setInt(13, u.pendingAmountRub);

                ps.setLong(14, u.chatId);

                updated = ps.executeUpdate();
            }

            if (updated == 0) {
                String insert = """
                        INSERT INTO users
                        (chat_id, state, verified, attempts_left,
                         verification_started_at, channel_link,
                         verification_video_file_id, pending_post_type,
                         last_start_at, last_callback_at,
                         payment_approved, payment_claimed_at,
                         pending_scheduled_at, pending_amount_rub)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

                    ps.setInt(11, u.paymentApproved ? 1 : 0);

                    if (u.paymentClaimedAt == null) ps.setNull(12, Types.BIGINT);
                    else ps.setLong(12, u.paymentClaimedAt);

                    if (u.pendingScheduledAtEpochSec == null) ps.setNull(13, Types.BIGINT);
                    else ps.setLong(13, u.pendingScheduledAtEpochSec);

                    if (u.pendingAmountRub == null) ps.setNull(14, Types.INTEGER);
                    else ps.setInt(14, u.pendingAmountRub);

                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- POSTS ----------

    public PostRecord findLatestPendingPost(long chatId) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
             SELECT id, chat_id, type, media_type, media_file_id, caption, status,
                    queue_position, scheduled_at, amount_rub
             FROM posts
             WHERE chat_id = ? AND status = 'PENDING_PAYMENT'
             ORDER BY id DESC
             LIMIT 1
             """)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                PostRecord p = new PostRecord();
                p.id = rs.getLong("id");
                p.chatId = rs.getLong("chat_id");
                p.type = PostType.valueOf(rs.getString("type"));
                p.mediaType = rs.getString("media_type");
                p.mediaFileId = rs.getString("media_file_id");
                p.caption = rs.getString("caption");
                p.status = rs.getString("status");
                p.queuePosition = rs.getInt("queue_position");

                long epoch = rs.getLong("scheduled_at");
                p.scheduledAt = Instant.ofEpochSecond(epoch).atZone(MOSCOW_ZONE).toLocalDateTime();

                int amt = rs.getInt("amount_rub");
                p.amountRub = rs.wasNull() ? null : amt;

                return p;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void updatePostAfterPayment(long postId, String status, int queuePos, LocalDateTime scheduledAt) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
             UPDATE posts
             SET status = ?, queue_position = ?, scheduled_at = ?
             WHERE id = ?
             """)) {

            long epoch = scheduledAt.atZone(MOSCOW_ZONE).toEpochSecond();
            ps.setString(1, status);
            ps.setInt(2, queuePos);
            ps.setLong(3, epoch);
            ps.setLong(4, postId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

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
                       (chat_id, type, media_type, media_file_id, caption,
                        status, queue_position, scheduled_at, amount_rub)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setLong(1, p.chatId);
            ps.setString(2, p.type.name());
            ps.setString(3, p.mediaType);
            ps.setString(4, p.mediaFileId);
            ps.setString(5, p.caption);
            ps.setString(6, p.status);
            ps.setInt(7, p.queuePosition);

            long epoch = p.scheduledAt.atZone(MOSCOW_ZONE).toEpochSecond();
            ps.setLong(8, epoch);

            if (p.amountRub == null) ps.setNull(9, Types.INTEGER);
            else ps.setInt(9, p.amountRub);

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

    // ✅ НОВОЕ: очередь "моментальных" (и синхронизированных с ними SCHEDULED_TIME)
    public int countInstantLikeQueue() {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COUNT(*)
                     FROM posts
                     WHERE status IN ('INSTANT','PUBLISHING')
                       AND type IN ('INSTANT','INSTANT_PIN','INSTANT_STORY','SCHEDULED_TIME')
                     """)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- ПУБЛИКАЦИЯ (для воркера) ----------

    public List<PostRecord> findDuePostsForPublish(long nowEpochSeconds, int limit) {
        List<PostRecord> out = new ArrayList<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
                 SELECT id, chat_id, type, media_type, media_file_id, caption, status,
                        queue_position, scheduled_at, amount_rub
                 FROM posts
                 WHERE status IN ('INSTANT','QUEUED') AND scheduled_at <= ?
                 ORDER BY scheduled_at ASC, queue_position ASC, id ASC
                 LIMIT ?
             """)) {
            ps.setLong(1, nowEpochSeconds);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PostRecord p = new PostRecord();
                    p.id = rs.getLong("id");
                    p.chatId = rs.getLong("chat_id");
                    p.type = PostType.valueOf(rs.getString("type"));
                    p.mediaType = rs.getString("media_type");
                    p.mediaFileId = rs.getString("media_file_id");
                    p.caption = rs.getString("caption");
                    p.status = rs.getString("status");
                    p.queuePosition = rs.getInt("queue_position");

                    long epoch = rs.getLong("scheduled_at");
                    p.scheduledAt = Instant.ofEpochSecond(epoch).atZone(MOSCOW_ZONE).toLocalDateTime();

                    int amt = rs.getInt("amount_rub");
                    p.amountRub = rs.wasNull() ? null : amt;

                    out.add(p);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public boolean markPostPublishing(long postId) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
                 UPDATE posts
                 SET status = 'PUBLISHING', last_error = NULL
                 WHERE id = ? AND status IN ('INSTANT','QUEUED')
             """)) {
            ps.setLong(1, postId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void markPostPublished(long postId, int messageId) {
        long nowEpoch = Instant.now().getEpochSecond();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
                 UPDATE posts
                 SET status = 'PUBLISHED',
                     published_message_id = ?,
                     published_at = ?,
                     last_error = NULL
                 WHERE id = ?
             """)) {
            ps.setInt(1, messageId);
            ps.setLong(2, nowEpoch);
            ps.setLong(3, postId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void markPostFailed(long postId, String error) {
        long nowEpoch = Instant.now().getEpochSecond();
        String err = (error == null) ? "unknown" : error;
        if (err.length() > 1000) err = err.substring(0, 1000);

        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
                 UPDATE posts
                 SET status = 'FAILED',
                     published_at = ?,
                     last_error = ?
                 WHERE id = ?
             """)) {
            ps.setLong(1, nowEpoch);
            ps.setString(2, err);
            ps.setLong(3, postId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public long savePostAndReturnId(PostRecord p) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO posts
                   (chat_id, type, media_type, media_file_id, caption,
                    status, queue_position, scheduled_at, amount_rub)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """, Statement.RETURN_GENERATED_KEYS)) {

            ps.setLong(1, p.chatId);
            ps.setString(2, p.type.name());
            ps.setString(3, p.mediaType);
            ps.setString(4, p.mediaFileId);
            ps.setString(5, p.caption);
            ps.setString(6, p.status);
            ps.setInt(7, p.queuePosition);

            long epoch = p.scheduledAt.atZone(MOSCOW_ZONE).toEpochSecond();
            ps.setLong(8, epoch);

            if (p.amountRub == null) ps.setNull(9, Types.INTEGER);
            else ps.setInt(9, p.amountRub);

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    p.id = id;
                    return id;
                }
            }

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    p.id = id;
                    return id;
                }
            }

            throw new RuntimeException("Cannot get generated id for inserted post");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public PostRecord findPostById(long postId) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
             SELECT id, chat_id, type, media_type, media_file_id, caption, status,
                    queue_position, scheduled_at, amount_rub
             FROM posts
             WHERE id = ?
             LIMIT 1
             """)) {
            ps.setLong(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                PostRecord p = new PostRecord();
                p.id = rs.getLong("id");
                p.chatId = rs.getLong("chat_id");
                p.type = PostType.valueOf(rs.getString("type"));
                p.mediaType = rs.getString("media_type");
                p.mediaFileId = rs.getString("media_file_id");
                p.caption = rs.getString("caption");
                p.status = rs.getString("status");
                p.queuePosition = rs.getInt("queue_position");

                long epoch = rs.getLong("scheduled_at");
                p.scheduledAt = Instant.ofEpochSecond(epoch).atZone(MOSCOW_ZONE).toLocalDateTime();

                int amt = rs.getInt("amount_rub");
                p.amountRub = rs.wasNull() ? null : amt;

                return p;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void updatePostStatus(long postId, String status) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("""
             UPDATE posts
             SET status = ?
             WHERE id = ?
             """)) {
            ps.setString(1, status);
            ps.setLong(2, postId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}