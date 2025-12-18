package com.example.femdombot;

import com.example.femdombot.config.BotConfig;
import com.example.femdombot.db.Db;
import com.example.femdombot.db.PostRecord;
import com.example.femdombot.db.UserRecord;
import com.example.femdombot.model.PostType;
import com.example.femdombot.model.UserState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendVideoNote;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class FemdomBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(FemdomBot.class);
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    // Ручная оплата
    private static final String PAY_CARD = "2204 2402 2094 6620";
    private static final String PAY_BANK_LABEL = "OZON БАНК";

    private static final String CB_PAY_I_PAID = "PAY_I_PAID";
    private static final String CB_PAY_BACK = "PAY_BACK";
    private static final String CB_PAY_ADMIN_OK_PREFIX = "PAY_ADMIN_OK:";
    private static final String CB_PAY_ADMIN_NO_PREFIX = "PAY_ADMIN_NO:";

    // ✅ НОВОЕ: модерация "Истории" (кружочек) админом
    private static final String CB_STORY_ADMIN_OK_PREFIX = "STORY_ADMIN_OK:";
    private static final String CB_STORY_ADMIN_NO_PREFIX = "STORY_ADMIN_NO:";

    // куда публикуем
    private final long publishChatId;

    // воркер публикации
    private final ScheduledExecutorService publishExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean publishWorkerStarted = new AtomicBoolean(false);

    private final BotConfig cfg;
    private final Db db;

    private final Map<Long, Long> lastCallbackMap = new ConcurrentHashMap<>();
    private final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private final DateTimeFormatter MSK_TIME_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public FemdomBot(BotConfig cfg, Db db) {
        this.cfg = cfg;
        this.db = db;
        this.publishChatId = cfg.getPublishChatId();
    }

    @Override
    public String getBotUsername() {
        return cfg.getUsername();
    }

    @Override
    public String getBotToken() {
        return cfg.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            } else if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            log.error("Error handling update", e);
        }
    }

    public void startPublishWorker() {
        if (!publishWorkerStarted.compareAndSet(false, true)) return;

        publishExecutor.scheduleAtFixedRate(() -> {
            try {
                publishDuePosts();
            } catch (Exception e) {
                log.error("Publish worker error", e);
            }
        }, 5, 30, TimeUnit.SECONDS);

        log.info("Publish worker started. publishChatId={}", publishChatId);
    }

    private void handleMessage(Message msg) throws TelegramApiException {
        long chatId = msg.getChatId();
        User user = msg.getFrom();
        UserRecord u = db.findOrCreateUser(chatId);

        // Защита от двойного /start
        if (msg.hasText() && msg.getText().startsWith("/start")) {
            if (protectDoubleStart(u)) {
                return;
            }
            if (u.verified && u.state == UserState.VERIFIED) {
                sendMainMenu(u);
            } else if (u.state == UserState.BANNED) {
                sendText(chatId, "🚫 Ваш доступ к функционалу временно приостановлен.\n\nПо всем вопросам свяжитесь с администрацией.");
            } else {
                u.state = UserState.WAIT_ROLE;
                db.saveUser(u);
                sendStartMessage(u);
            }
            return;
        }

        // Команда /admin
        if (msg.hasText() && msg.getText().equals("/admin")) {
            if (cfg.getAdmins().contains(user.getId().longValue())) {
                sendAdminPanel(chatId);
            } else {
                sendText(chatId, "🔒 Команда доступна только администраторам.");
            }
            return;
        }

        // Проверка: это видео/кружок для верификации?
        if (msg.hasVideo() || msg.hasVideoNote()) {
            if (u.state == UserState.WAIT_VERIFICATION_VIDEO) {
                handleVerificationVideo(u, msg);
                return;
            }
        }

        // Текстовые/медийные сообщения по состояниям
        if (u.state == UserState.WAIT_POST_CONTENT) {
            handlePostContent(u, msg);
            return;
        }

        if (msg.hasText()) {
            switch (u.state) {
                case WAIT_CHANNEL_LINK -> handleChannelLink(u, msg.getText());
                default -> sendText(chatId, "Пожалуйста, используйте кнопки под сообщением 🙂");
            }
        }
    }

    private boolean protectDoubleStart(UserRecord u) {
        long now = System.currentTimeMillis();
        if (u.lastStartAt != null && now - u.lastStartAt < cfg.getStartCooldownMs()) {
            return true;
        }
        u.lastStartAt = now;
        db.saveUser(u);
        return false;
    }

    // -------------------- CALLBACK HANDLER --------------------

    private void handleCallback(CallbackQuery cb) throws TelegramApiException {
        long chatId = cb.getMessage().getChatId();
        long userId = cb.getFrom().getId();
        String data = cb.getData();

        // Антиспам по коллбэкам
        long now = System.currentTimeMillis();
        long last = lastCallbackMap.getOrDefault(userId, 0L);
        if (now - last < cfg.getCallbackCooldownMs()) {
            AnswerCallbackQuery answer = new AnswerCallbackQuery(cb.getId());
            answer.setText("⏱ Не так быстро, пожалуйста 🙂");
            answer.setShowAlert(false);
            execute(answer);
            return;
        }
        lastCallbackMap.put(userId, now);

        UserRecord u = db.findOrCreateUser(chatId);
        if (data == null) return;

        if (data.equals("ROLE_SUB")) {
            handleRoleSub(u, cb);
        } else if (data.equals("ROLE_DOMINA")) {
            handleRoleDomina(u, cb);
        } else if (data.equals("TERMS_DECLINE")) {
            handleTermsDecline(u, cb);
        } else if (data.equals("TERMS_ACCEPT")) {
            handleTermsAccept(u, cb);
        } else if (data.equals("START_VERIFICATION")) {
            handleStartVerification(u, cb);
        } else if (data.equals("BACK_TO_MENU")) {
            handleBackToMenu(u, cb);
        } else if (data.equals("REPOST_DONE")) {
            handleRepostDone(u, cb);
        } else if (data.equals("RETRY_VERIFICATION")) {
            handleRetryVerification(u, cb);
        } else if (data.startsWith("ADMIN_APPROVE:")) {
            handleAdminDecision(cb, true);
        } else if (data.startsWith("ADMIN_REJECT:")) {
            handleAdminDecision(cb, false);
        } else if (data.equals("MAIN_MENU")) {
            sendMainMenu(u);
        } else if (data.equals("GET_PROMO_POST")) {
            sendPromoPostInstruction(u.chatId);
            answerOk(cb);
        } else if (data.equals("SHOW_RULES")) {
            sendPublicationRules(u.chatId);
            answerOk(cb);
        } else if (data.equals("SHOW_TARIFFS")) {
            sendTariffsInfo(u.chatId);
            answerOk(cb);
        } else if (data.startsWith("POST_")) {
            handlePostTypeCallback(u, cb);
        } else if (data.equals(CB_PAY_I_PAID)) {
            handleUserPaid(u, cb);
        } else if (data.equals(CB_PAY_BACK)) {
            // отменяем процесс оплаты и возвращаем в меню
            u.pendingPostType = null;
            u.paymentApproved = false;
            u.paymentClaimedAt = null;
            u.state = UserState.VERIFIED;
            db.saveUser(u);
            sendMainMenu(u);
            answerOk(cb);
        } else if (data.startsWith(CB_PAY_ADMIN_OK_PREFIX)) {
            handleAdminPaymentDecision(cb, true);
        } else if (data.startsWith(CB_PAY_ADMIN_NO_PREFIX)) {
            handleAdminPaymentDecision(cb, false);
        } else if (data.startsWith(CB_STORY_ADMIN_OK_PREFIX)) {
            handleAdminStoryDecision(cb, true);
        } else if (data.startsWith(CB_STORY_ADMIN_NO_PREFIX)) {
            handleAdminStoryDecision(cb, false);
        }
    }

    private void answerOk(CallbackQuery cb) throws TelegramApiException {
        AnswerCallbackQuery a = new AnswerCallbackQuery(cb.getId());
        execute(a);
    }

    // -------------------- START / ROLE / TERMS --------------------

    private void sendStartMessage(UserRecord u) throws TelegramApiException {
        String text = """
                Здравствуйте! ✨
                
                Для начала работы с ботом необходимо пройти короткую регистрацию ✅
                
                ⚠️ Учтите: сервис разработан и доступен только для девушек.
                """;

        InlineKeyboardButton subBtn = new InlineKeyboardButton();
        subBtn.setText("🐷 Раб/рабыня");
        subBtn.setCallbackData("ROLE_SUB");

        InlineKeyboardButton dominaBtn = new InlineKeyboardButton();
        dominaBtn.setText("👸🏼 Домина");
        dominaBtn.setCallbackData("ROLE_DOMINA");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(subBtn, dominaBtn)
        ));

        SendMessage sm = new SendMessage(String.valueOf(u.chatId), text);
        sm.setReplyMarkup(kb);
        execute(sm);
    }

    private void handleRoleSub(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        String text = """
                😔 К сожалению, данный сервис предназначен только для Госпож и не доступен для использования в роли раба/рабыни.
                
                Вы можете вернуться в меню и ознакомиться с информацией ещё раз.
                """;

        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("⬅️ Вернуться в меню");
        back.setCallbackData("BACK_TO_MENU");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(back)));

        SendMessage sm = new SendMessage(String.valueOf(u.chatId), text);
        sm.setReplyMarkup(kb);
        execute(sm);
        answerOk(cb);
    }

    private void handleRoleDomina(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        u.state = UserState.WAIT_TERMS_DECISION;
        db.saveUser(u);

        String text = """
                👸🏼 Условия доступа к функционалу бота
                
                • Доступ к регистрации открыт только для девушек.
                • Каждая новая пользовательница проходит обязательную проверку личности.
                • Участие возможно строго с 18 лет.
                
                Пожалуйста, учтите:
                • Опция покупки рекламы активируется после подтверждения регистрации.
                • Нарушение правил ведет к аннулированию доступа без возможности восстановления.
                • В случае удаления постов за нарушения, оплата за размещение не возвращается.
                """;

        InlineKeyboardButton decline = new InlineKeyboardButton();
        decline.setText("❌ Отказываюсь");
        decline.setCallbackData("TERMS_DECLINE");

        InlineKeyboardButton accept = new InlineKeyboardButton();
        accept.setText("✅ Принимаю");
        accept.setCallbackData("TERMS_ACCEPT");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(decline, accept)));

        SendMessage sm = new SendMessage(String.valueOf(u.chatId), text);
        sm.setReplyMarkup(kb);
        execute(sm);
        answerOk(cb);
    }

    private void handleTermsDecline(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        String text = """
                Очень жаль 🥺
                
                Без принятия правил доступ к функционалу бота может быть ограничен.
                Если вы передумаете — вернитесь в меню и начните регистрацию заново.
                """;

        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("⬅️ Вернуться в меню");
        back.setCallbackData("BACK_TO_MENU");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(back)));

        SendMessage sm = new SendMessage(String.valueOf(u.chatId), text);
        sm.setReplyMarkup(kb);
        execute(sm);
        answerOk(cb);
    }

    private void handleTermsAccept(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        u.state = UserState.WAIT_VERIFICATION_VIDEO;
        db.saveUser(u);

        String text = """
                ✅ Отлично! Давайте подтвердим вашу личность 🌟
                
                • Нажмите «🎥 Начать проверку»
                • Запишите короткое видео (до 20 секунд), как кружочек
                • На выполнение — 10 минут
                • У вас 5 попыток
                
                Нажимайте «Начать проверку» только когда будете готовы и расслаблены 🖤
                """;

        InlineKeyboardButton start = new InlineKeyboardButton();
        start.setText("🎥 Начать проверку");
        start.setCallbackData("START_VERIFICATION");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(start)));

        SendMessage sm = new SendMessage(String.valueOf(u.chatId), text);
        sm.setReplyMarkup(kb);
        execute(sm);
        answerOk(cb);
    }

    private void handleStartVerification(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        if (u.attemptsLeft <= 0) {
            sendText(u.chatId, "❌ Вы исчерпали количество попыток верификации. Доступ временно приостановлен.");
            answerOk(cb);
            return;
        }

        u.verificationStartedAt = System.currentTimeMillis();
        u.state = UserState.WAIT_VERIFICATION_VIDEO;
        db.saveUser(u);

        String text = """
                ✅ Верификация (легко и быстро) ✨
                
                Запишите короткое видео/кружочек так, чтобы:
                • лицо было четко видно
                • голос был хорошо слышен
                
                Произнесите вслух и очень четко слова:
                • Момент
                • Вода
                • Дом
                
                ⏰ У вас 10 минут на отправку видео с момента получения этого сообщения.
                """;

        sendText(u.chatId, text);
        answerOk(cb);
    }

    private void handleBackToMenu(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        if (u.verified && u.state == UserState.VERIFIED) {
            sendMainMenu(u);
        } else {
            u.state = UserState.WAIT_ROLE;
            db.saveUser(u);
            sendStartMessage(u);
        }
        answerOk(cb);
    }

    private void handleRetryVerification(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        if (u.attemptsLeft <= 0) {
            sendText(u.chatId, "❌ Количество попыток верификации исчерпано. Доступ к функционалу бота временно ограничен.");
            answerOk(cb);
            return;
        }

        u.state = UserState.WAIT_ROLE;
        db.saveUser(u);
        sendStartMessage(u);
        answerOk(cb);
    }

    // -------------------- Верификация видео --------------------

    private void handleVerificationVideo(UserRecord u, Message msg) throws TelegramApiException {
        long now = System.currentTimeMillis();
        if (u.verificationStartedAt == null) {
            u.verificationStartedAt = now;
            db.saveUser(u);
        }

        long diff = now - u.verificationStartedAt;
        long maxMillis = 10 * 60 * 1000L;

        if (diff > maxMillis) {
            u.attemptsLeft = Math.max(0, u.attemptsLeft - 1);
            if (u.attemptsLeft <= 0) {
                u.state = UserState.BANNED;
            } else {
                u.state = UserState.WAIT_ROLE;
            }
            db.saveUser(u);

            String text = "❌ Проверка не пройдена.\n\n" +
                    "Причина: видео было отправлено по истечении 10 минут.\n" +
                    "У вас осталось " + u.attemptsLeft + " попытки(ок).\n\n" +
                    "Чтобы попробовать снова, введите /start.";
            sendText(u.chatId, text);
            return;
        }

        String fileId;
        if (msg.hasVideoNote()) {
            fileId = msg.getVideoNote().getFileId();
        } else if (msg.hasVideo()) {
            fileId = msg.getVideo().getFileId();
        } else {
            sendText(u.chatId, "Пожалуйста, отправьте именно видео или «кружочек».");
            return;
        }

        u.verificationVideoFileId = fileId;
        u.state = UserState.WAIT_CHANNEL_LINK;
        db.saveUser(u);

        String text = """
                Спасибо! 🖤
                
                Теперь, пожалуйста, отправьте ссылку на ваш канал:
                • https://t.me/имя_канала
                или
                • @имя_канала
                """;
        sendText(u.chatId, text);
    }

    private void handleChannelLink(UserRecord u, String text) throws TelegramApiException {
        String t = text.trim();
        if (!isValidChannelLink(t)) {
            sendText(u.chatId, "Пожалуйста, отправьте корректную ссылку вида https://t.me/имя_канала или @имя_канала.");
            return;
        }

        u.channelLink = t;
        u.state = UserState.WAIT_REPOST_CONFIRM;
        db.saveUser(u);

        String message = "✅ Благодарим! Ссылка на ваш канал принята: " + t + "\n\n" +
                "Теперь выполните важное требование:\n" +
                "• Опубликуйте промо-пост о нашем сервисе у себя на канале.\n" +
                "• Пост должен быть размещен без последующего удаления.\n\n" +
                "Материалы для публикации получите по кнопке ниже 👇";

        InlineKeyboardButton getPost = new InlineKeyboardButton();
        getPost.setText("📨 Получить пост");
        getPost.setCallbackData("GET_PROMO_POST");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(getPost)));

        SendMessage sm = new SendMessage(String.valueOf(u.chatId), message);
        sm.setReplyMarkup(kb);
        execute(sm);
    }

    private boolean isValidChannelLink(String t) {
        return t.matches("(?i)^(https?://t\\.me/\\S+|@\\w+)$");
    }

    private void sendPromoPostInstruction(long chatId) throws TelegramApiException {
        SendPhoto sp = new SendPhoto();
        sp.setChatId(String.valueOf(chatId));

        InputStream is = getClass().getClassLoader().getResourceAsStream("1.jpg");
        if (is != null) {
            sp.setPhoto(new InputFile(is, "1.jpg"));
        } else {
            log.warn("1.jpg not found in resources");
            sp.setPhoto(new InputFile("https://example.com/1.jpg"));
        }

        // ✅ Зашитая ссылка: @Findom__Femdom_ -> https://t.me/+_406Bbw8k8EyYjJi
        String caption = """
                🔥 В этом канале собраны самые лучшие Госпожи, чье присутствие будоражит, а влияние безгранично! Это пространство, где формируются новые грани власти и рождается жгучее желание.
                
                ✅ Каждый представленный профиль тщательно отобран и проверен на реальность, подлинность силы и безупречность воздействия. Все, кто публикуются, реальны. Нам можно доверять!
                
                Погрузись в атмосферу, где каждая Госпожа — это произведение искусства соблазна и контроля. Открой для себя эксклюзивные грани смелых фантазий и позволь своим тайным желаниям говорить за тебя!
                
                ➡️ Присоединяйся к миру истинного превосходства: <a href="https://t.me/+_406Bbw8k8EyYjJi">@Findom__Femdom_</a>
                """;

        sp.setCaption(caption);
        sp.setParseMode("HTML"); // ✅ важно для кликабельной ссылки

        InlineKeyboardButton done = new InlineKeyboardButton();
        done.setText("✅ Репост выполнен");
        done.setCallbackData("REPOST_DONE");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(done)));
        sp.setReplyMarkup(kb);

        execute(sp);
    }

    private void handleRepostDone(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        if (u.state != UserState.WAIT_REPOST_CONFIRM) {
            answerOk(cb);
            return;
        }

        u.state = UserState.WAIT_ADMIN_APPROVAL;
        db.saveUser(u);

        sendText(u.chatId, "✅ Спасибо! Заявка отправлена на проверку администрации. Ожидайте решения 🖤");

        for (Long adminId : cfg.getAdmins()) {
            String text = "🆕 Новая заявка на доступ\n\n" +
                    "Пользователь: @" + safeUsername(cb.getFrom()) + " (" + u.chatId + ")\n" +
                    "Канал: " + Optional.ofNullable(u.channelLink).orElse("не указан") + "\n\n" +
                    "Проверьте наличие промо-поста и решите судьбу заявки.";

            SendMessage sm = new SendMessage(String.valueOf(adminId), text);

            InlineKeyboardButton approve = new InlineKeyboardButton();
            approve.setText("✅ Одобрить");
            approve.setCallbackData("ADMIN_APPROVE:" + u.chatId);

            InlineKeyboardButton reject = new InlineKeyboardButton();
            reject.setText("❌ Отклонить");
            reject.setCallbackData("ADMIN_REJECT:" + u.chatId);

            InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(approve, reject)));
            sm.setReplyMarkup(kb);
            execute(sm);

            if (u.verificationVideoFileId != null) {
                SendVideoNote vn = new SendVideoNote();
                vn.setChatId(String.valueOf(adminId));
                vn.setVideoNote(new InputFile(u.verificationVideoFileId));
                execute(vn);
            }
        }

        answerOk(cb);
    }

    private String safeUsername(User tgUser) {
        if (tgUser == null) return "unknown";
        String u = tgUser.getUserName();
        return (u == null || u.isBlank()) ? "id:" + tgUser.getId() : u;
    }

    // -------------------- Решение админа по верификации --------------------

    private void handleAdminDecision(CallbackQuery cb, boolean approve) throws TelegramApiException {
        long adminId = cb.getFrom().getId();
        if (!cfg.getAdmins().contains(adminId)) {
            AnswerCallbackQuery answer = new AnswerCallbackQuery(cb.getId());
            answer.setText("⛔ Только администратор может использовать эту кнопку.");
            answer.setShowAlert(true);
            execute(answer);
            return;
        }

        String data = cb.getData();
        String[] parts = data.split(":");
        if (parts.length != 2) {
            answerOk(cb);
            return;
        }

        long userChatId = Long.parseLong(parts[1]);
        UserRecord u = db.findOrCreateUser(userChatId);

        if (approve) {
            u.verified = true;
            u.state = UserState.VERIFIED;
            db.saveUser(u);

            sendMainMenu(u);
            AnswerCallbackQuery answer = new AnswerCallbackQuery(cb.getId());
            answer.setText("✅ Пользователь одобрен.");
            execute(answer);
        } else {
            if (u.attemptsLeft <= 1) {
                u.attemptsLeft = 0;
                u.state = UserState.BANNED;
                u.verified = false;
                db.saveUser(u);

                String msg = """
                        ❌ К сожалению, нам пришлось отказать вам в доступе.
                        
                        Возможные причины:
                        • некорректное выполнение условий
                        • отсутствие промо-поста
                        • нарушение правил площадки
                        
                        Лимит попыток исчерпан. Доступ к функционалу временно ограничен.
                        """;
                sendText(u.chatId, msg);
            } else {
                u.attemptsLeft = u.attemptsLeft - 1;
                u.verified = false;
                db.saveUser(u);

                String msg = "❌ К сожалению, доступ отклонён.\n\n" +
                        "Возможные причины: условия/промо-пост/правила площадки.\n\n" +
                        "У вас осталось попыток: " + u.attemptsLeft + ".\n" +
                        "Вы можете пройти верификацию ещё раз 👇";

                InlineKeyboardButton retry = new InlineKeyboardButton();
                retry.setText("🔁 Пройти верификацию ещё раз");
                retry.setCallbackData("RETRY_VERIFICATION");

                InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(retry)));

                SendMessage smUser = new SendMessage(String.valueOf(u.chatId), msg);
                smUser.setReplyMarkup(kb);
                execute(smUser);
            }

            AnswerCallbackQuery answer = new AnswerCallbackQuery(cb.getId());
            answer.setText("❌ Доступ отклонён.");
            execute(answer);
        }
    }

    // -------------------- Главное меню / правила / тарифы --------------------

    private void sendMainMenu(UserRecord u) throws TelegramApiException {
        u.state = UserState.VERIFIED;
        u.verified = true;
        db.saveUser(u);

        int queued = db.countUserQueuedPosts(u.chatId);

        String text = """
                🖤 Добро пожаловать!
                
                📌 Ваш статус: %d из 10 запланированных публикаций в очереди.
                
                Выберите формат размещения ниже ✨
                """.formatted(queued);

        InlineKeyboardButton rulesBtn = new InlineKeyboardButton();
        rulesBtn.setText("📅 Правила и график публикации");
        rulesBtn.setCallbackData("SHOW_RULES");

        InlineKeyboardButton tariffsInfoBtn = new InlineKeyboardButton();
        tariffsInfoBtn.setText("💎 Тарифы размещения");
        tariffsInfoBtn.setCallbackData("SHOW_TARIFFS");

        InlineKeyboardButton bInstant = new InlineKeyboardButton();
        bInstant.setText(PostType.INSTANT.getTitle());
        bInstant.setCallbackData("POST_INSTANT");

        InlineKeyboardButton bInstantPin = new InlineKeyboardButton();
        bInstantPin.setText(PostType.INSTANT_PIN.getTitle());
        bInstantPin.setCallbackData("POST_INSTANT_PIN");

        InlineKeyboardButton bInstantStory = new InlineKeyboardButton();
        bInstantStory.setText(PostType.INSTANT_STORY.getTitle());
        bInstantStory.setCallbackData("POST_INSTANT_STORY");

        InlineKeyboardButton bStory = new InlineKeyboardButton();
        bStory.setText(PostType.STORY.getTitle());
        bStory.setCallbackData("POST_STORY");

        InlineKeyboardButton bVip = new InlineKeyboardButton();
        bVip.setText(PostType.VIP.getTitle());
        bVip.setCallbackData("POST_VIP");

        InlineKeyboardButton bStandard = new InlineKeyboardButton();
        bStandard.setText(PostType.STANDARD.getTitle());
        bStandard.setCallbackData("POST_STANDARD");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(rulesBtn),
                List.of(tariffsInfoBtn),
                List.of(bInstant),
                List.of(bInstantPin),
                List.of(bInstantStory),
                List.of(bStory),
                List.of(bVip),
                List.of(bStandard)
        ));

        SendMessage sm = new SendMessage(String.valueOf(u.chatId), text);
        sm.setReplyMarkup(kb);
        execute(sm);
    }

    private void sendPublicationRules(long chatId) throws TelegramApiException {
        String rules = """
                📅 Правила и график публикации постов
                
                ⏱ Моментальные форматы:
                • ⚡ Моментальный пост
                • 📌 Моментальный пост + закреп на 3 дня
                • 🚀 Двойной эффект: Моментальный пост + История
                Эти форматы выходят максимально быстро после подтверждения оплаты 🖤
                
                📸 История:
                • Пользователь отправляет одно фото или одно видео + текст
                • История попадает администратору на одобрение/отклонение
                • В случае одобрения, пост попадает в историю.
                
                👑 VIP-пост:
                • Отдельная приоритетная очередь
                • Каждый день в 20:00 по Москве публикуется 1 VIP-пост
                
                ✨ Стандартный пост:
                • Публикуется в 20:00 по Москве, если на день нет VIP-поста
                """;

        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("🏠 В главное меню");
        back.setCallbackData("MAIN_MENU");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(back)));

        SendMessage sm = new SendMessage(String.valueOf(chatId), rules);
        sm.setReplyMarkup(kb);
        execute(sm);
    }

    private void sendTariffsInfo(long chatId) throws TelegramApiException {
        String tariffs = """
                💎 Тарифы размещения:
                
                • ⚡ Моментальный пост: 900 ₽
                • 📌 Моментальный пост + закреп на 3 дня: 1100 ₽
                • 🚀 Двойной эффект (пост + история): 1300 ₽
                • 📸 История: 500 ₽
                • 👑 VIP-пост: 600 ₽
                • ✨ Стандартный пост: 400 ₽
                """;

        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("🏠 В главное меню");
        back.setCallbackData("MAIN_MENU");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(back)));

        SendMessage sm = new SendMessage(String.valueOf(chatId), tariffs);
        sm.setReplyMarkup(kb);
        execute(sm);
    }

    // -------------------- Выбор тарифа -> ручная оплата --------------------

    private void handlePostTypeCallback(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        // если уже идёт процесс
        if (u.state == UserState.WAIT_PAYMENT_REVIEW) {
            sendText(u.chatId, """
                    ⏳ Ваш пост уже отправлен и ожидает подтверждения оплаты модератором 🖤
                    
                    Когда оплату подтвердят — вы сможете оформить новое размещение ✨
                    """);
            answerOk(cb);
            return;
        }

        if (u.state == UserState.WAIT_PAYMENT || u.state == UserState.WAIT_POST_CONTENT || u.state == UserState.WAIT_PAYMENT_REVIEW) {
            sendText(u.chatId, "⏳ У вас уже есть размещение в процессе. Давайте сначала завершим его 🙂");
            answerOk(cb);
            return;
        }

        PostType type;
        switch (cb.getData()) {
            case "POST_STANDARD" -> type = PostType.STANDARD;
            case "POST_STORY" -> type = PostType.STORY;
            case "POST_VIP" -> type = PostType.VIP;
            case "POST_INSTANT" -> type = PostType.INSTANT;
            case "POST_INSTANT_PIN" -> type = PostType.INSTANT_PIN;
            case "POST_INSTANT_STORY" -> type = PostType.INSTANT_STORY;
            default -> {
                answerOk(cb);
                return;
            }
        }

        u.pendingPostType = type.name();
        u.state = UserState.WAIT_PAYMENT;
        u.paymentApproved = false;
        u.paymentClaimedAt = null;
        db.saveUser(u);

        sendManualPaymentOffer(u, type);

        AnswerCallbackQuery answer = new AnswerCallbackQuery(cb.getId());
        answer.setText("💳 Реквизиты отправлены. После перевода нажмите «Я оплатила» 🙂");
        answer.setShowAlert(false);
        execute(answer);
    }

    private void sendManualPaymentOffer(UserRecord u, PostType type) throws TelegramApiException {
        int amount = getPriceRub(type);

        String html = """
                💳 <b>Оплата размещения</b> ✨
                
                🧾 Тариф: <b>%s</b>
                💰 Сумма к переводу: <b>%d ₽</b>
                
                🏦 Переведите на карту (<b>%s</b>):
                <code>%s</code>
                
                ✅ После перевода нажмите <b>«Я оплатила»</b> — и мы продолжим 🖤
                """.formatted(escapeHtml(type.getTitle()), amount, escapeHtml(PAY_BANK_LABEL), PAY_CARD);

        InlineKeyboardButton paid = new InlineKeyboardButton();
        paid.setText("✅ Я оплатила");
        paid.setCallbackData(CB_PAY_I_PAID);

        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("⬅️ Вернуться назад");
        back.setCallbackData(CB_PAY_BACK);

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(paid),
                List.of(back)
        ));

        sendHtml(u.chatId, html, kb);
    }

    private void handleUserPaid(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        if (u.pendingPostType == null) {
            sendText(u.chatId, "😅 Я не вижу выбранный тариф. Пожалуйста, выберите тип публикации заново.");
            sendMainMenu(u);
            answerOk(cb);
            return;
        }

        u.paymentClaimedAt = System.currentTimeMillis();
        u.paymentApproved = false;
        u.state = UserState.WAIT_POST_CONTENT;
        db.saveUser(u);

        PostType type = PostType.valueOf(u.pendingPostType);

        // ✅ НОВАЯ логика для "История": ждём кружочек, а потом отправим админу на Одобрить/Отклонить.
        if (type == PostType.STORY) {
            sendText(u.chatId, """
                    ✅ Благодарим! 🖤
                    
                    Теперь отправьте:
                    • одно фото или одно видео + текст (подпись) до 300 символов в одном сообщении
                    • без ссылок/контактов — мы добавим канал и личку автоматически
                    
                    После получения мы отправим пост администратору на одобрение/отклонение ✨
                    """);
            answerOk(cb);
            return;
        }

        // Обычные посты — как было раньше:
        sendText(u.chatId, """
                ✅ Благодарим за оплату! 🖤
                
                🕵️‍♀️ После проверки перевода модератором ваш пост появится в очереди ✨
                
                📩 Теперь отправьте материал для публикации:
                • Одно фото или одно видео + текст (подпись) в одном сообщении
                • Текст до 300 символов
                • Без ссылок/контактов — мы добавим их автоматически
                """);

        // Админам — заявка на проверку
        int amount = getPriceRub(type);
        String who = "@" + safeUsername(cb.getFrom());
        String timeMsk = LocalDateTime.now(MOSCOW_ZONE).format(MSK_TIME_FMT);

        for (Long adminId : cfg.getAdmins()) {
            String html = """
                    💸 <b>Проверка оплаты</b>
                    
                    ⏰ Время (МСК): <b>%s</b>
                    👤 Пользователь: <b>%s</b>
                    🆔 ChatID: <code>%d</code>
                    🧾 Тариф: <b>%s</b>
                    💰 Сумма: <b>%d ₽</b>
                    """.formatted(
                    escapeHtml(timeMsk),
                    escapeHtml(who),
                    u.chatId,
                    escapeHtml(type.getTitle()),
                    amount
            );

            InlineKeyboardButton ok = new InlineKeyboardButton();
            ok.setText("✅ Подтвердить");
            ok.setCallbackData(CB_PAY_ADMIN_OK_PREFIX + u.chatId);

            InlineKeyboardButton no = new InlineKeyboardButton();
            no.setText("❌ Отклонить");
            no.setCallbackData(CB_PAY_ADMIN_NO_PREFIX + u.chatId);

            InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(ok, no)));
            sendHtml(adminId, html, kb);
        }

        answerOk(cb);
    }

    private void handleAdminPaymentDecision(CallbackQuery cb, boolean approve) throws TelegramApiException {
        long adminId = cb.getFrom().getId();
        if (!cfg.getAdmins().contains(adminId)) {
            AnswerCallbackQuery a = new AnswerCallbackQuery(cb.getId());
            a.setText("⛔ Только администратор может использовать эту кнопку.");
            a.setShowAlert(true);
            execute(a);
            return;
        }

        String data = cb.getData();
        String prefix = approve ? CB_PAY_ADMIN_OK_PREFIX : CB_PAY_ADMIN_NO_PREFIX;
        long userChatId = Long.parseLong(data.substring(prefix.length()));

        UserRecord u = db.findOrCreateUser(userChatId);

        if (!approve) {
            // ОТКЛОНЕНО
            u.paymentApproved = false;
            u.paymentClaimedAt = null;
            u.pendingPostType = null;
            u.state = UserState.VERIFIED;
            db.saveUser(u);

            // если контент уже был принят как PENDING_PAYMENT — пометим REJECTED
            PostRecord pending = db.findLatestPendingPost(userChatId);
            if (pending != null) {
                db.updatePostAfterPayment(pending.id, "REJECTED", 0, LocalDateTime.now(MOSCOW_ZONE));
            }

            sendText(userChatId, """
                    ❌ Платёж не подтверждён
                    
                    Возможно, перевод ещё не дошёл или сумма/данные не совпали.
                    """);

            AnswerCallbackQuery a = new AnswerCallbackQuery(cb.getId());
            a.setText("❌ Отклонено. Пользователь уведомлён.");
            execute(a);
            return;
        }

        // ПОДТВЕРЖДЕНО
        u.paymentApproved = true;
        db.saveUser(u);

        // если пост уже есть — сразу переводим его в QUEUED/INSTANT
        PostRecord pending = db.findLatestPendingPost(userChatId);
        if (pending != null) {
            PostType type = pending.type;

            LocalDateTime scheduledAt;
            int queuePos = 0;
            String status;

            if (type == PostType.VIP || type == PostType.STANDARD) {
                queuePos = db.nextQueuePosition();
                status = "QUEUED";
                scheduledAt = estimateSchedule(type);
            } else {
                // ✅ Важно: STORY больше не попадает сюда, т.к. для STORY мы не создаём PENDING_PAYMENT пост.
                status = "INSTANT";
                scheduledAt = LocalDateTime.now(MOSCOW_ZONE);
            }

            db.updatePostAfterPayment(pending.id, status, queuePos, scheduledAt);

            // сбрасываем процесс
            u.paymentApproved = false;
            u.paymentClaimedAt = null;
            u.pendingPostType = null;
            u.state = UserState.VERIFIED;
            db.saveUser(u);

            sendText(userChatId, """
                    ✅ Оплата подтверждена! 🖤
                    
                    Ваш пост принят и поставлен в очередь на публикацию ✨
                    """);
        } else {
            // контента ещё нет
            sendText(userChatId, """
                    ✅ Оплата подтверждена! 🖤
                    
                    Теперь отправьте материал для публикации:
                    • Одно фото или одно видео + подпись одним сообщением
                    • Без ссылок/контактов ✨
                    """);

            u.state = UserState.WAIT_POST_CONTENT;
            db.saveUser(u);
        }

        AnswerCallbackQuery a = new AnswerCallbackQuery(cb.getId());
        a.setText("✅ Подтверждено.");
        execute(a);
    }

    private int getPriceRub(PostType type) {
        return switch (type) {
            case INSTANT -> 900;
            case INSTANT_PIN -> 1100;
            case INSTANT_STORY -> 1300;
            case STORY -> 500;
            case VIP -> 600;
            case STANDARD -> 400;
        };
    }

    // -------------------- Пост-контент --------------------

    private void handlePostContent(UserRecord u, Message msg) throws TelegramApiException {
        if (u.pendingPostType == null) {
            sendText(u.chatId, "Пожалуйста, сначала выберите тип публикации в меню 🙂");
            u.state = UserState.VERIFIED;
            db.saveUser(u);
            return;
        }

        PostType type = PostType.valueOf(u.pendingPostType);

        // ✅ "История": принимаем ОДНО фото ИЛИ ОДНО видео + ТЕКСТ (подпись) и добавляем канал/личку автоматически
        if (type == PostType.STORY) {

            if (!msg.hasPhoto() && !msg.hasVideo()) {
                sendText(u.chatId, "📸 Для тарифа «История» отправьте одно фото или одно видео с подписью одним сообщением.");
                return;
            }

            String caption = msg.getCaption();
            if (caption == null || caption.isBlank()) {
                sendText(u.chatId, "✍️ Пожалуйста, добавьте текст к вашей истории (подпись).");
                return;
            }
            if (caption.length() > 300) {
                sendText(u.chatId, "⚠️ Текст слишком длинный. Максимум 300 символов.");
                return;
            }
            if (caption.matches("(?i).*(t\\.me|https?://|@).*")) {
                sendText(u.chatId, "⚠️ Пожалуйста, не указывайте ссылки/контакты в тексте. Мы добавим их автоматически.");
                return;
            }

            String fileId;
            String mediaType;

            if (msg.hasPhoto()) {
                List<PhotoSize> photos = msg.getPhoto();
                PhotoSize largest = photos.get(photos.size() - 1);
                fileId = largest.getFileId();
                mediaType = "PHOTO";
            } else {
                fileId = msg.getVideo().getFileId();
                mediaType = "VIDEO";
            }

            User tgUser = msg.getFrom();
            String userTag = buildUserTag(tgUser);

            String channelLink = (u.channelLink != null && !u.channelLink.isBlank())
                    ? u.channelLink
                    : "не указан";

            // ✅ Автоподстановка, как у обычных постов
            String finalCaption = caption + "\n\n" +
                    "✨Канал " + channelLink + "\n" +
                    "\uD83D\uDC8EЛичка " + userTag;

            String timeMsk = LocalDateTime.now(MOSCOW_ZONE).format(MSK_TIME_FMT);
            int amount = getPriceRub(PostType.STORY);

            // Сохраняем как отдельную историю (НЕ в очередь воркера)
            PostRecord p = new PostRecord();
            p.chatId = u.chatId;
            p.type = PostType.STORY;
            p.mediaType = mediaType;
            p.mediaFileId = fileId;
            p.caption = finalCaption;          // ✅ теперь реальная подпись
            p.queuePosition = 0;
            p.scheduledAt = LocalDateTime.now(MOSCOW_ZONE);
            p.status = "STORY_REVIEW";

            long storyPostId = db.savePostAndReturnId(p);

            sendText(u.chatId, """
        📩 История получена 🖤
        
        Сейчас отправляем её администратору на одобрение/отклонение.
        """);

            // Админу — карточка + кнопки + сам медиа-файл С подписью
            for (Long adminId : cfg.getAdmins()) {
                String html = """
            📸 <b>История — модерация</b>
            
            ⏰ Время (МСК): <b>%s</b>
            👤 Пользователь: <b>%s</b>
            🆔 ChatID: <code>%d</code>
            🧾 Тариф: <b>%s</b>
            💰 Сумма: <b>%d ₽</b>
            ✨ Канал: <b>%s</b>
            💎 Личка: <b>%s</b>
            🆔 StoryID: <code>%d</code>
            """.formatted(
                        escapeHtml(timeMsk),
                        escapeHtml("@" + safeUsername(tgUser)),
                        u.chatId,
                        escapeHtml(PostType.STORY.getTitle()),
                        amount,
                        escapeHtml(channelLink),
                        escapeHtml(userTag),
                        storyPostId
                );

                InlineKeyboardButton ok = new InlineKeyboardButton();
                ok.setText("✅ Одобрить");
                ok.setCallbackData(CB_STORY_ADMIN_OK_PREFIX + storyPostId);

                InlineKeyboardButton no = new InlineKeyboardButton();
                no.setText("❌ Отклонить");
                no.setCallbackData(CB_STORY_ADMIN_NO_PREFIX + storyPostId);

                InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(ok, no)));
                sendHtml(adminId, html, kb);

                if ("VIDEO".equalsIgnoreCase(mediaType)) {
                    SendVideo sv = new SendVideo();
                    sv.setChatId(String.valueOf(adminId));
                    sv.setVideo(new InputFile(fileId));
                    sv.setCaption(finalCaption); // ✅ подпись с каналом/личкой
                    execute(sv);
                } else {
                    SendPhoto sp = new SendPhoto();
                    sp.setChatId(String.valueOf(adminId));
                    sp.setPhoto(new InputFile(fileId));
                    sp.setCaption(finalCaption); // ✅ подпись с каналом/личкой
                    execute(sp);
                }
            }

            // ✅ сбрасываем процесс у пользователя
            u.paymentApproved = false;
            u.paymentClaimedAt = null;
            u.pendingPostType = null;
            u.state = UserState.VERIFIED;
            db.saveUser(u);

            return;
        }

        if (!msg.hasPhoto() && !msg.hasVideo()) {
            sendText(u.chatId, "📎 Отправьте одно фото или одно видео с подписью одним сообщением.");
            return;
        }

        String caption = msg.getCaption();
        if (caption == null || caption.isBlank()) {
            sendText(u.chatId, "✍️ Пожалуйста, добавьте текст к вашему посту (подпись).");
            return;
        }
        if (caption.length() > 300) {
            sendText(u.chatId, "⚠️ Текст слишком длинный. Максимум 300 символов.");
            return;
        }
        if (caption.matches("(?i).*(t\\.me|https?://|@).*")) {
            sendText(u.chatId, "⚠️ Пожалуйста, не указывайте ссылки/контакты в тексте поста. Отправьте текст без ссылок.");
            return;
        }

        String fileId;
        String mediaType;

        if (msg.hasPhoto()) {
            List<PhotoSize> photos = msg.getPhoto();
            PhotoSize largest = photos.get(photos.size() - 1);
            fileId = largest.getFileId();
            mediaType = "PHOTO";
        } else {
            fileId = msg.getVideo().getFileId();
            mediaType = "VIDEO";
        }

        User tgUser = msg.getFrom();
        String userTag = buildUserTag(tgUser);

        String channelLink = (u.channelLink != null && !u.channelLink.isBlank())
                ? u.channelLink
                : "не указан";

        String finalCaption = caption + "\n\n" +
                "✨Канал " + channelLink + "\n" +
                "\uD83D\uDC8EЛичка " + userTag;

        boolean approved = u.paymentApproved;

        LocalDateTime scheduledAt = LocalDateTime.now(MOSCOW_ZONE);
        int queuePos = 0;
        String status;

        if (approved) {
            if (type == PostType.VIP || type == PostType.STANDARD) {
                queuePos = db.nextQueuePosition();
                status = "QUEUED";
                scheduledAt = estimateSchedule(type);
            } else {
                status = "INSTANT";
                scheduledAt = LocalDateTime.now(MOSCOW_ZONE);
            }
        } else {
            status = "PENDING_PAYMENT";
        }

        PostRecord p = new PostRecord();
        p.chatId = u.chatId;
        p.type = type;
        p.mediaFileId = fileId;
        p.caption = finalCaption;
        p.queuePosition = queuePos;
        p.scheduledAt = scheduledAt;
        p.status = status;
        p.mediaType = mediaType;

        db.savePost(p);

        if (approved) {
            // сброс процесса
            u.paymentApproved = false;
            u.paymentClaimedAt = null;
            u.pendingPostType = null;
            u.state = UserState.VERIFIED;
            db.saveUser(u);

            InlineKeyboardButton back = new InlineKeyboardButton();
            back.setText("🏠 В главное меню");
            back.setCallbackData("MAIN_MENU");
            InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(back)));

            String text;
            if (type == PostType.VIP || type == PostType.STANDARD) {
                String dateStr = scheduledAt.toLocalDate().format(DATE_FMT);
                String queueName = (type == PostType.VIP) ? "VIP-очередь" : "стандартную очередь";
                text = "✅ Поздравляем! Ваш пост принят 🖤\n\n" +
                        "Он добавлен в " + queueName + " под номером №" + queuePos + ".\n" +
                        "Ориентировочная дата выхода: " + dateStr + " в 20:00 по Москве ✨";
            } else {
                text = "✅ Ваш пост принят 🖤\n\n" +
                        "Тип публикации: " + type.getTitle() + "\n" +
                        "Этот формат выходит без ожидания очереди ✨";
            }

            SendMessage sm = new SendMessage(String.valueOf(u.chatId), text);
            sm.setReplyMarkup(kb);
            execute(sm);
        } else {
            // ждём модерацию оплаты
            u.state = UserState.WAIT_PAYMENT_REVIEW;
            db.saveUser(u);

            sendText(u.chatId, """
                    📩 Материал получен! Спасибо 🖤
                    
                    ⏳ Сейчас ждём подтверждение оплаты модератором.
                    Как только подтвердят — ваш пост появится в очереди ✨
                    """);
        }
    }

    // ✅ НОВОЕ: решение админа по "Истории"
    private void handleAdminStoryDecision(CallbackQuery cb, boolean approve) throws TelegramApiException {
        long adminId = cb.getFrom().getId();
        if (!cfg.getAdmins().contains(adminId)) {
            AnswerCallbackQuery a = new AnswerCallbackQuery(cb.getId());
            a.setText("⛔ Только администратор может использовать эту кнопку.");
            a.setShowAlert(true);
            execute(a);
            return;
        }

        String data = cb.getData();
        String prefix = approve ? CB_STORY_ADMIN_OK_PREFIX : CB_STORY_ADMIN_NO_PREFIX;

        long storyId;
        try {
            storyId = Long.parseLong(data.substring(prefix.length()));
        } catch (Exception e) {
            answerOk(cb);
            return;
        }

        PostRecord p = db.findPostById(storyId);
        if (p == null) {
            AnswerCallbackQuery a = new AnswerCallbackQuery(cb.getId());
            a.setText("⚠️ История не найдена (возможно уже обработана).");
            a.setShowAlert(true);
            execute(a);
            return;
        }

        if (approve) {
            db.updatePostStatus(storyId, "STORY_APPROVED");

            // ✅ обязательное уведомление клиента (как просили)
            InlineKeyboardButton back = new InlineKeyboardButton();
            back.setText("⬅️ Вернуться в меню");
            back.setCallbackData("MAIN_MENU");

            InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(back)));

            SendMessage smUser = new SendMessage(String.valueOf(p.chatId),
                    approve
                            ? "✅ Ваша история одобрена, она поставлена в очередь."
                            : "❌ Ваша история отклонена."
            );
            smUser.setReplyMarkup(kb);
            execute(smUser);

            AnswerCallbackQuery a = new AnswerCallbackQuery(cb.getId());
            a.setText("✅ Одобрено. Клиент уведомлён.");
            execute(a);
        } else {
            db.updatePostStatus(storyId, "STORY_REJECTED");

            // ✅ обязательное уведомление клиента
            InlineKeyboardButton back = new InlineKeyboardButton();
            back.setText("⬅️ Вернуться в меню");
            back.setCallbackData("MAIN_MENU");

            InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(back)));

            SendMessage smUser = new SendMessage(String.valueOf(p.chatId),
                    approve
                            ? "✅ Ваша история одобрена, она поставлена в очередь."
                            : "❌ Ваша история отклонена."
            );
            smUser.setReplyMarkup(kb);
            execute(smUser);

            AnswerCallbackQuery a = new AnswerCallbackQuery(cb.getId());
            a.setText("❌ Отклонено. Клиент уведомлён.");
            execute(a);
        }
    }

    private String buildUserTag(User tgUser) {
        if (tgUser == null) return "не указан";
        String username = tgUser.getUserName();
        if (username != null && !username.isBlank()) return "@" + username;
        return "id" + tgUser.getId();
    }

    // -------------------- Публикация воркером --------------------

    private void publishDuePosts() {
        long nowEpoch = Instant.now().getEpochSecond();
        List<PostRecord> due = db.findDuePostsForPublish(nowEpoch, 10);
        if (due.isEmpty()) return;

        for (PostRecord p : due) {
            // защита от дублей
            if (!db.markPostPublishing(p.id)) continue;

            try {
                Message sent = sendPostToPublishChat(p);
                db.markPostPublished(p.id, sent.getMessageId());
                log.info("Published post id={} to chat={}", p.id, publishChatId);
            } catch (TelegramApiException e) {
                db.markPostFailed(p.id, e.getMessage());
                log.error("Failed to publish post id={} to chat={}", p.id, publishChatId, e);
            } catch (Exception e) {
                db.markPostFailed(p.id, e.toString());
                log.error("Unexpected publish error post id={}", p.id, e);
            }
        }
    }

    private Message sendPostToPublishChat(PostRecord p) throws TelegramApiException {
        String target = String.valueOf(publishChatId);

        if ("VIDEO".equalsIgnoreCase(p.mediaType)) {
            SendVideo sv = new SendVideo();
            sv.setChatId(target);
            sv.setVideo(new InputFile(p.mediaFileId));
            sv.setCaption(p.caption);
            return execute(sv);
        }

        // default PHOTO
        SendPhoto sp = new SendPhoto();
        sp.setChatId(target);
        sp.setPhoto(new InputFile(p.mediaFileId));
        sp.setCaption(p.caption);
        return execute(sp);
    }

    // Оценка времени выхода (МСК)
    private LocalDateTime estimateSchedule(PostType type) {
        LocalDateTime nowMsk = LocalDateTime.now(MOSCOW_ZONE);

        if (type == PostType.INSTANT
                || type == PostType.INSTANT_PIN
                || type == PostType.INSTANT_STORY
                || type == PostType.STORY) {
            // STORY тут не участвует в публикации воркером, но оставляем как было (не влияет).
            return nowMsk;
        }

        LocalDateTime today20 = nowMsk.toLocalDate().atTime(20, 0);
        LocalDateTime firstSlot = nowMsk.isBefore(today20) ? today20 : today20.plusDays(1);

        if (type == PostType.VIP) {
            int vipAhead = db.countQueuedPostsByType(PostType.VIP);
            return firstSlot.plusDays(vipAhead);
        }

        if (type == PostType.STANDARD) {
            int vipQueued = db.countQueuedPostsByType(PostType.VIP);
            int stdQueued = db.countQueuedPostsByType(PostType.STANDARD);
            int slotIndex = vipQueued + stdQueued;
            return firstSlot.plusDays(slotIndex);
        }

        return nowMsk;
    }

    // -------------------- Админ-панель --------------------

    private void sendAdminPanel(long chatId) throws TelegramApiException {
        String text = """
                🛠 Админ-панель
                
                • Вы получаете заявки на верификацию с кнопками «Одобрить» / «Отклонить».
                • Вы получаете проверки оплат с кнопками «Подтвердить» / «Отклонить».
                • Для «Истории» вы получаете фото/видео с кнопками «Одобрить» / «Отклонить».
                • Публикация постов может быть реализована отдельным воркером/cron из очереди БД.
                """;
        sendText(chatId, text);
    }

    // -------------------- Helpers --------------------

    private void sendText(long chatId, String text) throws TelegramApiException {
        SendMessage sm = new SendMessage(String.valueOf(chatId), text);
        execute(sm);
    }

    private void sendHtml(long chatId, String html, InlineKeyboardMarkup kb) throws TelegramApiException {
        SendMessage sm = new SendMessage(String.valueOf(chatId), html);
        sm.setParseMode("HTML");
        if (kb != null) sm.setReplyMarkup(kb);
        execute(sm);
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}