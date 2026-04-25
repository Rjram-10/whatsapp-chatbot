package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiseaseSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final OllamaService ollamaService;

    public String extractKeywords(String text) {
        if (text == null) return "";
        return text.toLowerCase()
            .replaceAll("[^a-z ]", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    public List<DiseaseMatch> findSimilarDiseases(String symptomsText, int topK) {
        try {
            // Clean up query text (Smart Layer)
            String enrichedQuery = extractKeywords(symptomsText);

            // Get embedding for user's symptom description
            float[] queryEmbedding = ollamaService.getEmbedding(enrichedQuery);
            String vectorStr = toVectorString(queryEmbedding);

            // Search using cosine similarity (Threshold increased to 0.5)
            String sql = """
                SELECT 
                    disease_name,
                    symptoms,
                    description,
                    precautions,
                    1 - (embedding <=> ?::vector) as similarity
                FROM disease_embeddings
                WHERE 1 - (embedding <=> ?::vector) > 0.5
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;

            List<DiseaseMatch> rawResults = jdbcTemplate.query(sql,
                (rs, rowNum) -> new DiseaseMatch(
                    rs.getString("disease_name"),
                    rs.getString("symptoms"),
                    rs.getString("description"),
                    rs.getString("precautions"),
                    rs.getDouble("similarity")
                ),
                vectorStr, vectorStr, vectorStr, topK * 3 // fetch extra for scoring
            );

            // Post-filter clinical scoring
            return rawResults.stream()
                .map(d -> {
                    double score = d.similarity();
                    String diseaseLower = d.diseaseName().toLowerCase();
                    
                    // Simple clinical scoring based on keywords matching disease specific flags
                    if (enrichedQuery.contains("left") && enrichedQuery.contains("abdomen")) {
                        if (diseaseLower.contains("appendicitis")) score -= 0.2;
                        if (diseaseLower.contains("diverticulitis")) score += 0.2;
                    }
                    if (enrichedQuery.contains("right") && enrichedQuery.contains("abdomen")) {
                        if (diseaseLower.contains("appendicitis")) score += 0.2;
                    }

                    return new DiseaseMatch(
                        d.diseaseName(),
                        d.symptoms(),
                        d.description(),
                        d.precautions(),
                        score
                    );
                })
                .sorted((a, b) -> Double.compare(b.similarity(), a.similarity()))
                .limit(topK)
                .toList();

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
                "%d. %s\n" +
                "   Key symptoms: %s\n" +
                "   Why relevant: matches reported symptoms and context\n\n",
                i + 1,
                match.diseaseName(),
                match.symptoms()
            ));
        }

        return context.toString();
    }

    private String toVectorString(float[] embedding) {
        return "[" + java.util.stream.IntStream.range(0, embedding.length)
                .mapToObj(i -> String.format("%.6f", embedding[i]))
                .collect(Collectors.joining(",")) + "]";
    }

    public record DiseaseMatch(
        String diseaseName,
        String symptoms,
        String description,
        String precautions,
        double similarity
    ) {}
}
