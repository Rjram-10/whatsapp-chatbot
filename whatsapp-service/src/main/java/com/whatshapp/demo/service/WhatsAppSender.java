package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppSender {

    @Value("${meta.api.token}")
    private String token;
    @Value("${meta.phone.number.id}")
    private String phoneId;

    private final WebClient webClient = WebClient.create();

    public void send(String to, String text) {
        try {
            webClient.post()
                    .uri("https://graph.facebook.com/v19.0/" + phoneId + "/messages")
                    .header("Authorization", "Bearer " + token)
                    .bodyValue(Map.of(
                            "messaging_product", "whatsapp",
                            "to", to,
                            "type", "text",
                            "text", Map.of("body", text)
                    ))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) { log.error("Failed to send WhatsApp message: {}", e.getMessage()); }
    }
}
