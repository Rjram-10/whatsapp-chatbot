package com.whatshapp.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OllamaService {

    @Value("${ollama.base.url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:llama3.2}")
    private String ollamaModel;

    @Value("${ollama.embed.model:nomic-embed-text}")
    private String embedModel;

    private WebClient getClient() {
        return WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    public float[] getEmbedding(String text) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", embedModel);
        request.put("prompt", text);


        try {
            JsonNode response = getClient().post()
                    .uri("/api/embeddings")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            JsonNode embeddingNode = response.path("embedding");
            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }
            return embedding;

        } catch (Exception e) {
            log.error("Ollama embedding error: {}", e.getMessage());
            throw new RuntimeException("Failed to get embedding from Ollama", e);
        }
    }

    public String chat(String systemPrompt, List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        // Add system message
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        // Add conversation history
        messages.addAll(history);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ollamaModel);
        requestBody.put("messages", messages);
        requestBody.put("stream", false);
        requestBody.put("options", Map.of(
                "temperature", 0.7,
                "num_predict", 300
        ));

        try {
            JsonNode response = getClient().post()
                    .uri("/api/chat")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(java.time.Duration.ofSeconds(60))
                    .block();

            return response.path("message").path("content").asText();

        } catch (Exception e) {
            log.error("Ollama chat error: {}", e.getMessage());
            throw new RuntimeException("Failed to get response from Ollama", e);
        }
    }

    public boolean isAvailable() {
        try {
            getClient().get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
