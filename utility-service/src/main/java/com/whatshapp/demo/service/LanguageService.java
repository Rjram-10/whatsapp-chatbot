package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LanguageService {

    private final RedisService redisService;

    public String detectLanguage(String text) {
        if (text.matches(".*[\\u0900-\\u097F].*")) return "hi";
        return "en";
    }

    public String getOrSetLanguage(String user, String message) {
        String lang = redisService.getUserLanguage(user);
        if (lang != null) return lang;
        lang = detectLanguage(message);
        redisService.saveUserLanguage(user, lang);
        return lang;
    }
}
