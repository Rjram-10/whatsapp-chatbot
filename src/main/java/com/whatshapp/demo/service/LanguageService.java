package com.whatshapp.demo.service;

import com.whatshapp.demo.model.UserSession;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class LanguageService {

    // Devanagari Unicode block: \u0900–\u097F
    private static final Pattern HINDI_PATTERN = Pattern.compile("[\\u0900-\\u097F]");

    public String detect(String text, UserSession session) {
        if (HINDI_PATTERN.matcher(text).find()) return "hi";
        // If user previously set a language, keep it
        if (session.getLanguage() != null) return session.getLanguage();
        return "en";
    }
}
