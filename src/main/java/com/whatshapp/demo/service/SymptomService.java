package com.whatshapp.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatshapp.demo.model.ChatMessage;
import com.whatshapp.demo.model.State;
import com.whatshapp.demo.model.UserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SymptomService {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    private final WebClient geminiClient = WebClient.builder()
            .baseUrl("https://generativelanguage.googleapis.com")
            .build();

    private final SessionService sessionService;

    public String startChat(String phone, String lang) {
        return "hi".equals(lang)
                ? "Theek hai, apne lakshan batayein. Kya takleef ho rahi hai?"
                : "Sure, please describe your symptoms. What are you experiencing?";
    }

    public String continueChat(String phone, String userMessage, UserSession session) {

        session.getChatHistory().add(new ChatMessage("user", userMessage));

        String systemPrompt = """
                You are a public health assistant for India. Respond in %s language only.
                Ask ONE follow-up question at a time about the user's symptoms.
                After 3-4 exchanges, list 2-3 possible conditions in simple words.
                Always end with a disclaimer to consult a doctor.
                Never give a definitive diagnosis. Keep responses short and simple.
                """.formatted("hi".equals(session.getLanguage()) ? "Hindi" : "English");

        String reply = callGemini(systemPrompt, session.getChatHistory());

        session.getChatHistory().add(new ChatMessage("assistant", reply));

        if (session.getChatHistory().size() >= 10) {
            session.setState(State.MENU);
            session.getChatHistory().clear();
            reply += "\n\n" + ("hi".equals(session.getLanguage())
                    ? "_(Menu ke liye kuch bhi bhejein)_"
                    : "_(Send anything to return to menu)_");
        }

        sessionService.save(session);
        return reply;
    }

    private String callGemini(String systemPrompt, List<ChatMessage> history) {
        List<Map<String, Object>> contents = new ArrayList<>();

        for (ChatMessage msg : history) {
            String role = "assistant".equals(msg.getRole()) ? "model" : "user";

            Map<String, Object> part = new HashMap<>();
            part.put("text", msg.getContent());

            Map<String, Object> content = new HashMap<>();
            content.put("role", role);
            content.put("parts", List.of(part));

            contents.add(content);
        }

        Map<String, Object> systemPart = new HashMap<>();
        systemPart.put("text", systemPrompt);

        Map<String, Object> systemInstruction = new HashMap<>();
        systemInstruction.put("parts", List.of(systemPart));

        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("maxOutputTokens", 300);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("system_instruction", systemInstruction);
        requestBody.put("contents", contents);
        requestBody.put("generationConfig", generationConfig);

        try {
            return geminiClient.post()
                    .uri("/v1beta/models/gemini-1.5-flash:generateContent?key=" + geminiApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(r -> r.path("candidates").get(0)
                            .path("content")
                            .path("parts").get(0)
                            .path("text").asText())
                    .block();
        }catch (Exception e) {
        System.out.println(">>> GEMINI ERROR: " + e.getMessage());
        e.printStackTrace();
        return "Sorry, I am having trouble responding right now. Please try again.";
    }
    }
}