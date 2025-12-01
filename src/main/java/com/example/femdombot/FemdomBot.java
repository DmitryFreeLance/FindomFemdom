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
import org.telegram.telegrambots.meta.api.methods.send.*;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;

import java.time.ZoneId;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FemdomBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(FemdomBot.class);
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private final BotConfig cfg;
    private final Db db;

    private final Map<Long, Long> lastCallbackMap = new ConcurrentHashMap<>();

    private final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public FemdomBot(BotConfig cfg, Db db) {
        this.cfg = cfg;
        this.db = db;
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
            if (update.hasPreCheckoutQuery()) {
                handlePreCheckout(update.getPreCheckoutQuery());
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            } else if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            log.error("Error handling update", e);
        }
    }

    private void handlePreCheckout(PreCheckoutQuery query) throws TelegramApiException {
        AnswerPreCheckoutQuery answer = new AnswerPreCheckoutQuery();
        answer.setPreCheckoutQueryId(query.getId());
        answer.setOk(true);
        execute(answer);
    }

    private void handleMessage(Message msg) throws TelegramApiException {
        long chatId = msg.getChatId();
        User user = msg.getFrom();
        UserRecord u = db.findOrCreateUser(chatId);

        if (msg.hasSuccessfulPayment()) {
            handleSuccessfulPayment(u, msg);
            return;
        }

        // Защита от двойного /start
        if (msg.hasText() && msg.getText().startsWith("/start")) {
            if (protectDoubleStart(u)) {
                return;
            }
            if (u.verified && u.state == UserState.VERIFIED) {
                sendMainMenu(u);
            } else if (u.state == UserState.BANNED) {
                sendText(chatId, "Ваш доступ к функционалу временно приостановлен. По всем вопросам свяжитесь с администрацией.");
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

        // Текстовые сообщения по состояниям
        if (msg.hasText()) {
            switch (u.state) {
                case WAIT_CHANNEL_LINK -> handleChannelLink(u, msg.getText());
                case WAIT_POST_CONTENT -> handlePostContent(u, msg);
                default -> sendText(chatId, "Пожалуйста, используйте кнопки под сообщением.");
            }
        } else if (u.state == UserState.WAIT_POST_CONTENT) {
            handlePostContent(u, msg);
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

    private void handleSuccessfulPayment(UserRecord u, Message msg) throws TelegramApiException {
        SuccessfulPayment payment = msg.getSuccessfulPayment();
        String payload = payment.getInvoicePayload(); // то, что мы положим в setPayload()

        if (payload != null && payload.startsWith("POST_TYPE:")) {
            String typeName = payload.substring("POST_TYPE:".length());
            PostType type = PostType.valueOf(typeName);

            u.pendingPostType = type.name();
            u.state = UserState.WAIT_POST_CONTENT;
            db.saveUser(u);

            String text = """
                ✅ Оплата прошла успешно!

                Теперь отправьте материал для публикации.

                Пожалуйста:
                • Одно фото или одно видео + текст (подпись) в одном сообщении.
                • Текст до 300 символов.
                • Без ссылок на канал или контакты — мы добавим их автоматически.
                """;
            sendText(u.chatId, text);
        } else {
            sendText(u.chatId, "✅ Оплата прошла успешно.");
        }
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
        }
    }

    private void handleRetryVerification(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        // если попыток нет — просто сообщаем и ничего не даём сделать
        if (u.attemptsLeft <= 0) {
            sendText(u.chatId, "❌ Количество попыток верификации исчерпано. Доступ к функционалу бота временно ограничен.");
            answerOk(cb);
            return;
        }

        // запускаем регистрацию заново с выбора роли
        u.state = UserState.WAIT_ROLE;
        db.saveUser(u);

        sendStartMessage(u);
        answerOk(cb);
    }

    private void sendStartMessage(UserRecord u) throws TelegramApiException {
        String text = """
                Здравствуйте!
                
                Для начала работы с ботом необходимо пройти короткую регистрацию. ✅ Это ваш первый шаг к новым функциям.
                
                Учтите: сервис разработан и доступен только для девушек.
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
        long chatId = u.chatId;
        String text = """
                😔 К сожалению, данный сервис предназначен только для Госпож и не доступен для использования в роли раба/рабыни.
                
                Вы можете вернуться в главное меню и ознакомиться с информацией ещё раз.
                """;

        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("⬅️ Вернуться в меню");
        back.setCallbackData("BACK_TO_MENU");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(back)
        ));

        SendMessage sm = new SendMessage(String.valueOf(chatId), text);
        sm.setReplyMarkup(kb);
        execute(sm);
        answerOk(cb);
    }

    private void handleRoleDomina(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        u.state = UserState.WAIT_TERMS_DECISION;
        db.saveUser(u);

        String text = """
                👸🏼 Условия Доступа к Функционалу Бота
                
                Мы рады приветствовать вас! Для поддержания высокого стандарта и обеспечения безопасности, мы установили следующие условия для всех пользовательниц:
                
                • Доступ к регистрации открыт только для девушек.
                • Каждая новая пользовательница проходит обязательную проверку личности.
                • Участие возможно строго с 18 лет.
                
                Пожалуйста, учтите:
                • Опция покупки рекламы активируется после подтверждения регистрации.
                • Нарушение правил ведет к аннулированию доступа без возможности его восстановления.
                • В случае удаления постов за нарушения, оплата за размещение не возвращается.
                """;

        InlineKeyboardButton decline = new InlineKeyboardButton();
        decline.setText("❌ Отказываюсь");
        decline.setCallbackData("TERMS_DECLINE");

        InlineKeyboardButton accept = new InlineKeyboardButton();
        accept.setText("✅ Принимаю");
        accept.setCallbackData("TERMS_ACCEPT");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                List.of(decline, accept)
        ));

        SendMessage sm = new SendMessage(String.valueOf(u.chatId), text);
        sm.setReplyMarkup(kb);
        execute(sm);
        answerOk(cb);
    }

    private void handleTermsDecline(UserRecord u, CallbackQuery cb) throws TelegramApiException {
        String text = """
                Очень жаль, но без принятия данных правил доступ к функционалу бота может быть ограничен.
                
                Если вы передумаете, вы всегда можете вернуться в меню и начать регистрацию заново.
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
                Мы рады, что вы готовы присоединиться!
                
                Процесс подтверждения вашей личности сделан максимально простым и комфортным:
                
                • Начните: просто нажмите кнопку «Начать Проверку».
                • Получите: мы сразу предоставим вам четкое задание и подробную инструкцию.
                • Запишите: ваша задача — выполнить задание на коротком видео (до 20 секунд), словно делаете «кружочек» для друзей.
                • Не торопитесь: на выполнение у вас будет целых 10 минут.
                • Отправьте: пожалуйста, отправляйте только видео с заданием — это ускорит рассмотрение.
                
                ⚠️ Пожалуйста, учтите:
                • Мы даем вам 5 попыток для успешного прохождения верификации.
                • Если все попытки будут использованы, доступ к функционалу бота будет временно приостановлен.
                
                💡 Важный совет:
                • Нажимайте «Начать Проверку» только тогда, когда будете чувствовать себя полностью готовыми и расслабленными.
                """;

        InlineKeyboardButton start = new InlineKeyboardButton();
        start.setText("🎥 Начать Проверку");
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
                ✅ Ваш Шаг к Полному Доступу: Как Пройти Верификацию Легко!
                
                Приветствуем! Чтобы подтвердить вашу личность и открыть все возможности бота, выполните простое задание.
                
                Как это сделать:
                • Запишите короткое видео-сообщение (как обычный «кружочек»), убедившись, что:
                  • ваше лицо полностью и ясно видно;
                  • ваш голос отчетливо слышен;
                  • вы выполнили задание ниже.
                
                Ваше задание:
                Пожалуйста, произнесите вслух и очень четко эти слова:
                • Момент
                • Вода
                • Дом
                
                У вас будет 10 минут на отправку видео с момента получения этого сообщения.
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
                    "Причина: видео было отправлено по истечении отведенного времени. Превышен лимит в 10 минут.\n" +
                    "У вас осталось " + u.attemptsLeft + " попытки(ок) для прохождения верификации.\n\n" +
                    "Чтобы попробовать снова, пожалуйста, введите команду /start.";
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
                Спасибо за ваше время и внимание!
                
                Мы ценим ваше участие. Теперь, пожалуйста, поделитесь ссылкой на ваш канал.
                Это позволит нам подготовить почву для вашего успешного развития на нашей платформе.
                """;
        sendText(u.chatId, text);
    }

    private void handleChannelLink(UserRecord u, String text) throws TelegramApiException {
        String t = text.trim();
        if (!isValidChannelLink(t)) {
            sendText(u.chatId, "Пожалуйста, отправьте корректную ссылку на канал вида https://t.me/имя_канала или @имя_канала.");
            return;
        }

        u.channelLink = t;
        u.state = UserState.WAIT_REPOST_CONFIRM;
        db.saveUser(u);

        String message = "✅ Благодарим! Ссылка на ваш канал принята: " + t + "\n\n" +
                "Для продолжения и прохождения верификации, пожалуйста, выполните следующее ключевое требование:\n" +
                "• Опубликуйте промо-пост о нашем сервисе на вашем канале.\n" +
                "• Пост должен быть размещен без последующего удаления.\n" +
                "• Материалы для публикации приведены ниже (получите их по кнопке).\n\n" +
                "Контроль: администрация осуществит проверку наличия поста.\n";

        // Кнопка "Получить пост"
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

        // 1.jpg из ресурсов (src/main/resources/1.jpg)
        InputStream is = getClass().getClassLoader().getResourceAsStream("1.jpg");
        if (is != null) {
            sp.setPhoto(new InputFile(is, "1.jpg"));
        } else {
            // если картинка не найдена — отправим просто без фото (Telegram требует либо фото, либо будет ошибка)
            // здесь можно подставить fileId заранее загруженного фото, если есть
            log.warn("1.jpg not found in resources");
            sp.setPhoto(new InputFile("https://example.com/1.jpg")); // временный вариант, замените на свой
        }

        String caption = """
                🔥 В этом канале собраны самые лучшие Госпожи, чье присутствие будоражит, а влияние безгранично! Это пространство, где формируются новые грани власти и рождается жгучее желание.
                
                ✅ Каждый представленный профиль тщательно отобран и проверен на реальность, подлинность силы и безупречность воздействия. Все, кто публикуются, реальны. Нам можно доверять!
                
                Погрузись в атмосферу, где каждая Госпожа — это произведение искусства соблазна и контроля. Открой для себя эксклюзивные грани смелых фантазий и позволь своим тайным желаниям говорить за тебя!
                
                ➡️ Присоединяйся к миру истинного превосходства: @Findom__Femdom_
                
                Внимание: материал для публикации перед вами.
                """;
        sp.setCaption(caption);

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

        sendText(u.chatId, "Спасибо! Ваша заявка отправлена на проверку администрации. Ожидайте решения.");

        // Отправляем админам инфо
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

            // Отдельно отправим видео (если есть)
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

    // -------------------- Решение админа --------------------

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

            sendWelcomeAfterApprove(u);
            AnswerCallbackQuery answer = new AnswerCallbackQuery(cb.getId());
            answer.setText("✅ Пользователь одобрен.");
            execute(answer);
        } else {
            // Отклонение -> учитываем попытки
            // Если это была последняя попытка — баним без кнопки
            if (u.attemptsLeft <= 1) {
                u.attemptsLeft = 0;
                u.state = UserState.BANNED;
                u.verified = false;
                db.saveUser(u);

                String msg = """
                        К сожалению, нам пришлось отказать вам в доступе к сервису.
                        
                        Причина может быть связана с некорректным выполнением условий, отсутствием промо-поста или нарушением правил площадки.
                        
                        Если вы считаете, что произошла ошибка, вы можете связаться с администрацией для уточнения деталей.
                        
                        ❌ Лимит попыток верификации исчерпан. Доступ к функционалу бота временно ограничен.
                        """;
                sendText(u.chatId, msg);
            } else {
                // Попытки ещё есть — даём шанс пройти всё заново
                u.attemptsLeft = u.attemptsLeft - 1;
                u.verified = false;
                // тут можно оставить текущий state, но логичнее вернуть к началу воронки после нажатия кнопки
                db.saveUser(u);

                String msg = "К сожалению, нам пришлось отказать вам в доступе к сервису.\n\n" +
                        "Причина может быть связана с некорректным выполнением условий, отсутствием промо-поста или нарушением правил площадки.\n\n" +
                        "Если вы считаете, что произошла ошибка, вы можете связаться с администрацией для уточнения деталей.\n\n" +
                        "У вас осталось попыток: " + u.attemptsLeft + ". Вы можете пройти верификацию ещё раз.";

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

    private void sendWelcomeAfterApprove(UserRecord u) throws TelegramApiException {
        sendMainMenu(u);
    }

    private void sendMainMenu(UserRecord u) throws TelegramApiException {
        u.state = UserState.VERIFIED;
        u.verified = true;
        db.saveUser(u);

        int queued = db.countUserQueuedPosts(u.chatId);

        String text = """
                Добро пожаловать!
                
                Ваш статус: %d из 10 запланированных публикаций в очереди.
                Используйте кнопки ниже, чтобы создать новую запись или управлять существующими.
                
                Важно: не размещайте идентичные публикации одну за другой.
                """.formatted(queued);

        // Кнопки "Правила" и "Тарифы"
        InlineKeyboardButton rulesBtn = new InlineKeyboardButton();
        rulesBtn.setText("📅 Правила и график публикации");
        rulesBtn.setCallbackData("SHOW_RULES");

        InlineKeyboardButton tariffsInfoBtn = new InlineKeyboardButton();
        tariffsInfoBtn.setText("💎 Тарифы размещения");
        tariffsInfoBtn.setCallbackData("SHOW_TARIFFS");

        // Кнопки с типами постов (как у тебя в sendTariffMenu)
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
            • 📸 История
            Эти форматы выходят без ожидания основной очереди — максимально быстро после покупки/подтверждения.
                
            👑 VIP-пост:
            • Формируется отдельная приоритетная очередь.
            • Каждый день в 20:00 по Москве публикуется ровно один VIP-пост.
            • Пока в VIP-очереди есть записи, стандартные посты не выходят и ждут своей очереди.
                
            ✨ Стандартный пост:
            • Публикуется в 20:00 по Москве только в те дни, когда на этот день нет VIP-поста.
            • Позиция в очереди и ориентировочная дата выхода зависят от количества VIP-постов в очереди.
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
            
            • ⚡ Моментальный пост (выход без задержек): 900 ₽
              (Немедленная публикация сразу после покупки – не теряйте ни минуты!)
            
            • 📌 Моментальный пост + закреп на 3 дня: 1100 ₽
              (Гарантированная максимальная видимость! Ваш пост будет в ТОПе три дня подряд)
            
            • 🚀 Двойной эффект: Моментальный пост + История: 1300 ₽
              (Комбинируйте скорость и охват для полного погружения вашей аудитории!)
            
            • 📸 История: 500 ₽
              (Опубликуйте свою историю мгновенно и привлеките внимание!)
            
            • 👑 VIP-пост (приоритетный выход): 600 ₽
              (Ваш контент выйдет в прайм-тайм – ровно в 20:00 по Москве)
            
            • ✨ Стандартный пост в ленте: 400 ₽
              (Вашу идею увидит аудитория в общем потоке; пост выходит в 20:00, если в этот день нет VIP-поста)
            """;

        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("🏠 В главное меню");
        back.setCallbackData("MAIN_MENU");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(back)));

        SendMessage sm = new SendMessage(String.valueOf(chatId), tariffs);
        sm.setReplyMarkup(kb);
        execute(sm);
    }

    private void sendTariffMenu(long chatId) throws TelegramApiException {
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
                List.of(bInstant),
                List.of(bInstantPin),
                List.of(bInstantStory),
                List.of(bStory),
                List.of(bVip),
                List.of(bStandard)
        ));

        SendMessage sm = new SendMessage(String.valueOf(chatId), "Выберите тип публикации:");
        sm.setReplyMarkup(kb);
        execute(sm);
    }

    private void handlePostTypeCallback(UserRecord u, CallbackQuery cb) throws TelegramApiException {
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

        // запоминаем выбранный тип
        u.pendingPostType = type.name();
        u.state = UserState.VERIFIED; // пока ещё не ждём контент, ждём оплату
        db.saveUser(u);

        // отправляем invoice
        sendInvoiceForPost(u, type);

        AnswerCallbackQuery answer = new AnswerCallbackQuery(cb.getId());
        answer.setText("💳 Счёт на оплату отправлен. После оплаты бот попросит вас приложить пост.");
        answer.setShowAlert(false);
        execute(answer);
    }

    private void sendInvoiceForPost(UserRecord u, PostType type) throws TelegramApiException {
        SendInvoice invoice = new SendInvoice();
        invoice.setChatId(String.valueOf(u.chatId));
        invoice.setTitle(type.getTitle());
        invoice.setDescription(getDescriptionForType(type));
        invoice.setCurrency("RUB");
        invoice.setProviderToken(cfg.getPaymentProviderToken());

        // payload — то, по чему мы потом поймём, за что была оплата
        invoice.setPayload("POST_TYPE:" + type.name());
        invoice.setStartParameter("post_" + type.name().toLowerCase());

        int amountKopecks = getPriceKopecks(type);
        List<LabeledPrice> prices = List.of(new LabeledPrice(type.getTitle(), amountKopecks));
        invoice.setPrices(prices);

        execute(invoice);
    }

    private int getPriceKopecks(PostType type) {
        return switch (type) {
            case INSTANT -> 900 * 100;
            case INSTANT_PIN -> 1100 * 100;
            case INSTANT_STORY -> 1300 * 100;
            case STORY -> 500 * 100;
            case VIP -> 600 * 100;
            case STANDARD -> 400 * 100;
        };
    }

    private String getDescriptionForType(PostType type) {
        return switch (type) {
            case INSTANT -> "⚡ Моментальный пост — немедленная публикация без ожидания очереди.";
            case INSTANT_PIN -> "📌 Моментальный пост + закреп на 3 дня — максимальная видимость поста.";
            case INSTANT_STORY -> "🚀 Двойной эффект: Моментальный пост + История.";
            case STORY -> "📸 История — мгновенная публикация сторис.";
            case VIP -> "👑 VIP-пост — приоритетный выход в 20:00 по Москве.";
            case STANDARD -> "✨ Стандартный пост в ленте в 20:00 по Москве при отсутствии VIP-поста.";
        };
    }

    private void handlePostContent(UserRecord u, Message msg) throws TelegramApiException {
        if (u.pendingPostType == null) {
            sendText(u.chatId, "Пожалуйста, сначала выберите тип публикации в меню.");
            u.state = UserState.VERIFIED;
            db.saveUser(u);
            return;
        }

        if (!msg.hasPhoto() && !msg.hasVideo()) {
            sendText(u.chatId, "Отправьте одно фото или одно видео с подписью одним сообщением.");
            return;
        }

        String caption = msg.getCaption();
        if (caption == null || caption.isBlank()) {
            sendText(u.chatId, "Пожалуйста, добавьте текст к вашему посту (подпись).");
            return;
        }
        if (caption.length() > 300) {
            sendText(u.chatId, "Текст слишком длинный. Максимум 300 символов.");
            return;
        }
        // Проверяем только исходный текст пользователя, без служебного блока
        if (caption.matches("(?i).*(t\\.me|https?://|@).*")) {
            sendText(u.chatId, "Пожалуйста, не указывайте ссылки на канал или контакты в тексте поста. Отправьте текст без ссылок.");
            return;
        }

        String fileId;
        if (msg.hasPhoto()) {
            List<PhotoSize> photos = msg.getPhoto();
            PhotoSize largest = photos.get(photos.size() - 1);
            fileId = largest.getFileId();
        } else {
            fileId = msg.getVideo().getFileId();
        }

        // Формируем служебный блок снизу: тг канал / личка
        User tgUser = msg.getFrom();
        String username = tgUser != null ? tgUser.getUserName() : null;
        String userTag;
        if (username != null && !username.isBlank()) {
            userTag = "@" + username;
        } else if (tgUser != null) {
            userTag = "id" + tgUser.getId();
        } else {
            userTag = "не указан";
        }

        String channelLink = (u.channelLink != null && !u.channelLink.isBlank())
                ? u.channelLink
                : "не указан";

        String finalCaption = caption + "\n\n" +
                "тг канал: " + channelLink + "\n" +
                "личка: " + userTag;

        PostType type = PostType.valueOf(u.pendingPostType);

        // Примерное время выхода (учитывая МСК и очереди)
        LocalDateTime scheduledAt = estimateSchedule(type);

        int queuePos = 0;
        String status;

        // VIP и Стандарт — идут в очередь
        if (type == PostType.VIP || type == PostType.STANDARD) {
            queuePos = db.nextQueuePosition();
            status = "QUEUED";
        } else {
            // Моментальные форматы и История — без очереди
            status = "INSTANT";
        }

        PostRecord p = new PostRecord();
        p.chatId = u.chatId;
        p.type = type;
        p.mediaFileId = fileId;
        p.caption = finalCaption;
        p.queuePosition = queuePos;
        p.scheduledAt = scheduledAt;
        p.status = status;

        db.savePost(p);

        u.state = UserState.VERIFIED;
        u.pendingPostType = null;
        db.saveUser(u);

        // Ответ пользователю
        String text;
        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("🏠 В главное меню");
        back.setCallbackData("MAIN_MENU");
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(back)));

        if (type == PostType.VIP || type == PostType.STANDARD) {
            String dateStr = scheduledAt.toLocalDate().format(DATE_FMT);
            String queueName = (type == PostType.VIP) ? "VIP‑очередь" : "стандартную очередь";

            text = "✅ Поздравляем! Ваш пост принят!\n\n" +
                    "Он добавлен в " + queueName + " под номером №" + queuePos + ".\n" +
                    "Ориентировочная дата выхода: " + dateStr + " в 20:00 по Москве.\n\n" +
                    "Важно: точная дата может смещаться, так как VIP‑посты имеют приоритет " +
                    "и выходят каждый день в 20:00.";

        } else {
            text = "✅ Ваш пост принят!\n\n" +
                    "Тип публикации: " + type.getTitle() + "\n" +
                    "Этот формат выходит без ожидания очереди (моментальный выход).";
        }

        text += "\n\nЧтобы продолжить, нажмите «В главное меню» ниже.";

        SendMessage sm = new SendMessage(String.valueOf(u.chatId), text);
        sm.setReplyMarkup(kb);
        execute(sm);
    }

    // Примерная оценка времени выхода с учётом двух очередей и МСК
    private LocalDateTime estimateSchedule(PostType type) {
        // текущее время в МСК
        LocalDateTime nowMsk = LocalDateTime.now(MOSCOW_ZONE);

        // Моментальные форматы + История: выход «сейчас»
        if (type == PostType.INSTANT
                || type == PostType.INSTANT_PIN
                || type == PostType.INSTANT_STORY
                || type == PostType.STORY) {
            return nowMsk;
        }

        // Ближайший слот 20:00 по МСК
        LocalDateTime today20 = nowMsk.toLocalDate().atTime(20, 0);
        LocalDateTime firstSlot = nowMsk.isBefore(today20)
                ? today20
                : today20.plusDays(1);

        // 👑 VIP — своя очередь: каждый день один VIP
        if (type == PostType.VIP) {
            int vipAhead = db.countQueuedPostsByType(PostType.VIP); // сколько VIP уже в очереди
            return firstSlot.plusDays(vipAhead);
        }

        // ✨ Стандартный — выходит только когда нет VIP на день
        if (type == PostType.STANDARD) {
            int vipQueued = db.countQueuedPostsByType(PostType.VIP);
            int stdQueued = db.countQueuedPostsByType(PostType.STANDARD);

            int slotIndex = vipQueued + stdQueued;
            return firstSlot.plusDays(slotIndex);
        }

        return nowMsk;
    }

    // -------------------- Админ-панель (простая) --------------------

    private void sendAdminPanel(long chatId) throws TelegramApiException {
        String text = """
                🛠 Админ-панель
                
                • Вы автоматически получаете заявки на верификацию с кнопками «Одобрить» / «Отклонить».
                • Публикация постов может быть реализована через отдельный скрипт/cron, который берёт записи из очереди.
                """;
        sendText(chatId, text);
    }

    // -------------------- Вспомогательные методы --------------------

    private void sendText(long chatId, String text) throws TelegramApiException {
        SendMessage sm = new SendMessage(String.valueOf(chatId), text);
        execute(sm);
    }

    private void answerOk(CallbackQuery cb) throws TelegramApiException {
        AnswerCallbackQuery a = new AnswerCallbackQuery(cb.getId());
        execute(a);
    }
}