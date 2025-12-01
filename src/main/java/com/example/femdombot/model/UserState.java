package com.example.femdombot.model;

public enum UserState {
    NEW,                        // только /start
    WAIT_ROLE,                  // выбор Раб/рабыня или Домина
    WAIT_TERMS_DECISION,        // "Отказываюсь" / "Принимаю"
    WAIT_VERIFICATION_VIDEO,    // ждем кружок/видео
    WAIT_CHANNEL_LINK,          // ждем ссылку на канал
    WAIT_REPOST_CONFIRM,        // ждем "Репост выполнен"
    WAIT_ADMIN_APPROVAL,        // решение админа
    VERIFIED,                   // доступ к функционалу
    BANNED,                     // заблокирован (не прошел/отказан)
    WAIT_POST_CONTENT           // ждем фото/видео + текст поста
}