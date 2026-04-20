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
                You are a public health information assistant for India. Respond in %s language only.
                
                Guidelines:
                1. Ask ONE follow-up question at a time to narrow down symptoms.
                2. After exactly 3-4 exchanges, provide a list of 2-3 common health patterns or educational info related to the symptoms.
                3. USE EDUCATIONAL LABELS: Start the list with "Based on common health patterns, this is often seen in cases of:".
                4. AVOID the word "diagnosis". Use "educational health information" or "observed patterns" instead.
                5. ALWAYS conclude with: "This is educational info and NOT a professional diagnosis. Please consult a qualified doctor immediately."
                6. Ensure the response is completely generated and supportive.
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

        // 1. Add chat history with STRICT alternating roles (user/model)
        for (ChatMessage msg : history) {
            String role = "assistant".equals(msg.getRole()) ? "model" : "user";

            Map<String, Object> part = new HashMap<>();
            part.put("text", msg.getContent());

            Map<String, Object> content = new HashMap<>();
            content.put("role", role);
            content.put("parts", List.of(part));

            contents.add(content);
        }

        // 2. Build Generation Config
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("maxOutputTokens", 512);

        // 3. Construct Request Body with system_instruction
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", contents);
        requestBody.put("generationConfig", generationConfig);

        // Add proper system instruction
        Map<String, Object> sysPart = new HashMap<>();
        sysPart.put("text", systemPrompt);
        Map<String, Object> sysInstruction = new HashMap<>();
        sysInstruction.put("parts", List.of(sysPart));
        requestBody.put("system_instruction", sysInstruction);

        // 4. Add Safety Settings to prevent mid-sentence blocks
        List<Map<String, Object>> safetySettings = new ArrayList<>();
        String[] categories = {
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
        };
        for (String category : categories) {
            Map<String, Object> setting = new HashMap<>();
            setting.put("category", category);
            setting.put("threshold", "BLOCK_NONE");
            safetySettings.add(setting);
        }
        requestBody.put("safetySettings", safetySettings);

        try {
            return geminiClient.post()
                    .uri("/v1beta/models/gemini-flash-latest:generateContent")
                    .header("Content-Type", "application/json")
                    .header("X-goog-api-key", geminiApiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(r -> {
                        JsonNode candidates = r.path("candidates");
                        if (candidates.isMissingNode() || candidates.size() == 0) {
                            return "I apologize, but I cannot provide information on this topic. Please consult a medical professional.";
                        }
                        JsonNode candidate = candidates.get(0);
                        String text = candidate.path("content").path("parts").get(0).path("text").asText();
                        
                        if (text == null || text.trim().isEmpty()) {
                            return "Based on safety guidelines, I cannot complete this response. Please see a doctor for medical concerns.";
                        }
                        return text;
                    })
                    .block();

        } catch (Exception e) {
            System.out.println(">>> GEMINI ERROR: " + e.getMessage());
            e.printStackTrace();
            return "Sorry, I am having trouble responding right now. Please try again.";
        }
    }
}