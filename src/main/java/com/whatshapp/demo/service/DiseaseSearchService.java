package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiseaseSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final OllamaService ollamaService;

    public List<DiseaseMatch> findSimilarDiseases(String symptomsText, int topK) {
        try {
            // Get embedding for user's symptom description
            float[] queryEmbedding = ollamaService.getEmbedding(symptomsText);
            String vectorStr = toVectorString(queryEmbedding);

            // Search using cosine similarity
            String sql = """
                SELECT 
                    disease_name,
                    symptoms,
                    description,
                    precautions,
                    1 - (embedding <=> ?::vector) as similarity
                FROM disease_embeddings
                WHERE 1 - (embedding <=> ?::vector) > 0.3
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;

            return jdbcTemplate.query(sql,
                (rs, rowNum) -> new DiseaseMatch(
                    rs.getString("disease_name"),
                    rs.getString("symptoms"),
                    rs.getString("description"),
                    rs.getString("precautions"),
                    rs.getDouble("similarity")
                ),
                vectorStr, vectorStr, vectorStr, topK
            );

        } catch (Exception e) {
            log.error("Disease search error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public String buildContext(List<DiseaseMatch> matches) {
        if (matches.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("Based on medical knowledge, relevant conditions:\n\n");

        for (int i = 0; i < matches.size(); i++) {
            DiseaseMatch match = matches.get(i);
            context.append(String.format(
                "%d. %s (relevance: %.0f%%)\n" +
                "   Symptoms: %s\n" +
                "   Info: %s\n" +
                "   Precautions: %s\n\n",
                i + 1,
                match.diseaseName(),
                match.similarity() * 100,
                match.symptoms(),
                match.description(),
                match.precautions()
            ));
        }

        return context.toString();
    }

    private String toVectorString(float[] embedding) {
        String values = java.util.stream.IntStream.range(0, embedding.length)
                .mapToObj(i -> String.format("%.6f", embedding[i]))
                .collect(Collectors.joining(","));
        return "[" + values + "]";
    }

    public record DiseaseMatch(
        String diseaseName,
        String symptoms,
        String description,
        String precautions,
        double similarity
    ) {}
}
