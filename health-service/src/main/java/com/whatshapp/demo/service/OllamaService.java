package com.whatshapp.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService {

    @Value("${ollama.base.url:http://localhost:11434}")
    private String baseUrl;
    @Value("${ollama.model:llama3.2}")
    private String modelName;
    @Value("${ollama.embed.model:nomic-embed-text}")
    private String embedModel;

    private final WebClient webClient = WebClient.create();
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isAvailable() {
        try {
            webClient.get().uri(baseUrl + "/api/tags").retrieve().toBodilessEntity().block();
            return true;
        } catch (Exception e) { return false; }
    }

    public String chat(String systemPrompt, List<Map<String, String>> messages) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("system", systemPrompt);
        body.put("messages", messages);
        body.put("stream", false);

        JsonNode response = webClient.post().uri(baseUrl + "/api/chat").bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        return response.path("message").path("content").asText();
    }

    public float[] generateEmbedding(String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", embedModel);
        body.put("prompt", text);

        JsonNode response = webClient.post().uri(baseUrl + "/api/embeddings").bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        JsonNode embeddingNode = response.path("embedding");
        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) embedding[i] = (float) embeddingNode.get(i).asDouble();
        return embedding;
    }
}
