package com.example.femdombot;

import com.example.femdombot.config.BotConfig;
import com.example.femdombot.db.Db;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) throws Exception {
        BotConfig cfg = new BotConfig();
        Db db = new Db(cfg.getDbUrl());

        FemdomBot bot = new FemdomBot(cfg, db);

        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(bot);

        System.out.println("Bot started");
    }
}