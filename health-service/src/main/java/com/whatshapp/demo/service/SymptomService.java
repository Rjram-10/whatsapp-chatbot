package com.whatshapp.demo.service;

import com.whatshapp.demo.model.ChatMessage;
import com.whatshapp.demo.model.State;
import com.whatshapp.demo.model.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SymptomService {

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    private final SessionService sessionService;
    private final OllamaService ollamaService;
    private final DiseaseSearchService diseaseSearchService;

    public String startChat(String phone, String lang) {
        return "hi".equals(lang)
                ? "Theek hai, apne lakshan batayein. Kya takleef ho rahi hai?"
                : "Sure, please describe your symptoms. What are you experiencing?";
    }

    public String continueChat(String phone, String userMessage, UserSession session) {
        session.getChatHistory().add(new ChatMessage("user", userMessage));
        String reply;
        if (ollamaService.isAvailable()) {
            reply = continueWithOllama(userMessage, session) + "\n\n_(via Ollama)_";
        } else {
            log.warn("Ollama not available, falling back to Gemini");
            reply = continueWithGemini(session) + "\n\n_(via Gemini)_";
        }
        session.getChatHistory().add(new ChatMessage("assistant", reply));
        if (session.getChatHistory().size() >= 10) {
            session.setState(State.MENU);
            session.getChatHistory().clear();
            reply += "\n\n" + ("hi".equals(session.getLanguage()) ? "_(Menu ke liye kuch bhi bhejein)_" : "_(Send anything to return to menu)_");
        }
        sessionService.save(session);
        return reply;
    }

    private String continueWithOllama(String userMessage, UserSession session) {
        String lang = session.getLanguage();
        String context = "";
        if (session.getChatHistory().size() >= 2) {
            String allSymptoms = session.getChatHistory().stream().filter(m -> "user".equals(m.getRole())).map(ChatMessage::getContent).collect(Collectors.joining(". "));
            List<DiseaseSearchService.DiseaseMatch> matches = diseaseSearchService.findSimilarDiseases(allSymptoms, 3);
            if (!matches.isEmpty()) {
                context = diseaseSearchService.buildContext(matches);
            }
        }
        String systemPrompt = buildSystemPrompt(lang, context);
        List<Map<String, String>> history = session.getChatHistory().stream().map(msg -> {
                    Map<String, String> m = new HashMap<>();
                    m.put("role", msg.getRole());
                    m.put("content", msg.getContent());
                    return m;
                }).collect(Collectors.toList());
        try {
            return ollamaService.chat(systemPrompt, history);
        } catch (Exception e) {
            log.error("Ollama chat failed: {}", e.getMessage());
            return "hi".equals(lang) ? "Maafi, abhi jawab dene mein dikkat aa rahi hai." : "Sorry, trouble responding.";
        }
    }

    private String buildSystemPrompt(String lang, String ragContext) {
        String base = String.format("You are a public health assistant for India. Respond ONLY in %s language. Ask ONE follow-up question at a time. After 3-4 exchanges, list 2-3 possible conditions. ALWAYS end with: consult a doctor. Never give a definitive diagnosis. Keep responses under 100 words.", "hi".equals(lang) ? "Hindi" : "English");
        if (!ragContext.isEmpty()) base += "\n\nRelevant medical context:\n" + ragContext;
        return base;
    }

    private String continueWithGemini(UserSession session) {
        String lang = session.getLanguage();
        try {
            org.springframework.web.reactive.function.client.WebClient geminiClient = org.springframework.web.reactive.function.client.WebClient.builder().baseUrl("https://generativelanguage.googleapis.com").build();
            List<Map<String, Object>> contents = new ArrayList<>();
            for (ChatMessage msg : session.getChatHistory()) {
                Map<String, Object> content = new HashMap<>();
                content.put("role", "assistant".equals(msg.getRole()) ? "model" : "user");
                content.put("parts", List.of(Map.of("text", msg.getContent())));
                contents.add(content);
            }
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("system_instruction", Map.of("parts", List.of(Map.of("text", buildSystemPrompt(lang, "")))));
            requestBody.put("contents", contents);
            requestBody.put("generationConfig", Map.of("maxOutputTokens", 300));
            com.fasterxml.jackson.databind.JsonNode response = geminiClient.post().uri("/v1beta/models/gemini-1.5-flash:generateContent?key=" + geminiApiKey).header("Content-Type", "application/json").bodyValue(requestBody).retrieve().bodyToMono(com.fasterxml.jackson.databind.JsonNode.class).block();
            return response.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        } catch (Exception e) {
            return "hi".equals(lang) ? "Maafi, symptom checker available nahi hai." : "Sorry, symptom checker is unavailable.";
        }
    }
}
