package com.whatshapp.demo.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiseaseSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final OllamaService ollamaService;

    public List<DiseaseMatch> findSimilarDiseases(String symptoms, int limit) {
        try {
            float[] embedding = ollamaService.generateEmbedding(symptoms);
            String vectorStr = "[" + floatArrayToCommaString(embedding) + "]";
            String sql = "SELECT name, description, symptoms, precautions FROM diseases ORDER BY embedding <=> ?::vector LIMIT ?";
            return jdbcTemplate.query(sql, (rs, rowNum) -> new DiseaseMatch(
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("symptoms"),
                    rs.getString("precautions")
            ), vectorStr, limit);
        } catch (Exception e) {
            log.error("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String floatArrayToCommaString(float[] array) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) sb.append(",");
        }
        return sb.toString();
    }

    public String buildContext(List<DiseaseMatch> matches) {
        StringBuilder sb = new StringBuilder();
        for (DiseaseMatch m : matches) {
            sb.append("Disease: ").append(m.name).append("\n")
              .append("Description: ").append(m.description).append("\n")
              .append("Symptoms: ").append(m.symptoms).append("\n")
              .append("Precautions: ").append(m.precautions).append("\n\n");
        }
        return sb.toString();
    }

    @Data @AllArgsConstructor
    public static class DiseaseMatch {
        String name;
        String description;
        String symptoms;
        String precautions;
    }
}
