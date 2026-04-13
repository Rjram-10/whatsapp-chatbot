package com.whatshapp.demo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatshapp.demo.service.MessageRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WhatsAppController {

    private final MessageRouter messageRouter;

    @Value("${meta.verify.token}")
    private String verifyToken;

    // Meta calls this once to verify your webhook
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).build();
    }

    // All incoming messages come here
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody JsonNode payload) {
        // Respond 200 immediately — process async so Meta doesn't time out
        messageRouter.routeAsync(payload);
        return ResponseEntity.ok().build();
    }
}