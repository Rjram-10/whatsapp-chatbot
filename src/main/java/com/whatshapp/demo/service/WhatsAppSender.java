package com.whatshapp.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class WhatsAppSender {

    @Value("${meta.api.token}")
    private String token;

    @Value("${meta.phone.number.id}")
    private String phoneNumberId;

    private final WebClient webClient = WebClient.create("https://graph.facebook.com");

    public void send(String to, String message) {
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", message)
        );

        webClient.post()
                .uri("/v22.0/{phoneNumberId}/messages", phoneNumberId)
                .header("Authorization", "Bearer " + token)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(); // fire and forget
    }
}
