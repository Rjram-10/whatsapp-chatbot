package com.whatshapp.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TranslationService {

    private final WebClient webClient = WebClient.create();

    public String translate(String text, String targetLang) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if ("en".equals(targetLang) && isProbablyEnglish(text)) {
            return text;
        }
        try {
            return webClient.post()
                    .uri("http://localhost:5001/translate")
                    .bodyValue(Map.of(
                            "q", text,
                            "source", "auto",
                            "target", targetLang
                    ))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(r -> {
                        JsonNode node = r.get("translatedText");
                        return node != null ? node.asText() : text;
                    })
                    .blockOptional()
                    .orElse(text);
        } catch (Exception e) {
            return text;
        }
    }
    
    private boolean isProbablyEnglish(String text) {
        return text.matches("^[\\x00-\\x7F]*$");
    }
}
