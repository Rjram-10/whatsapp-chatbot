package com.whatshapp.demo.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DiseaseSearchService — pgvector similarity search with severity-weighted re-ranking.
 *
 * Flow:
 *  1. Parse user symptoms into a list.
 *  2. SeverityService repeats high-severity symptoms to amplify their embedding weight.
 *  3. Embed the weighted query → vector search returns top N*3 candidates.
 *  4. Re-rank candidates by severity-weighted symptom overlap.
 *  5. Return top N results.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiseaseSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final OllamaService ollamaService;
    private final SeverityService severityService;

    public List<DiseaseMatch> findSimilarDiseases(String symptoms, int limit) {
        try {
            // Step 1 – parse symptom string into a list
            List<String> symptomList = Arrays.asList(symptoms.split("[,\\.]+"));

            // Step 2 – build severity-weighted query (high-severity symptoms repeated)
            String weightedQuery = severityService.buildWeightedQuery(symptomList);
            log.debug("Severity-weighted query: {}", weightedQuery);

            // Step 3 – embed and run vector search (fetch 3× more than needed for re-ranking)
            float[] embedding = ollamaService.generateEmbedding(weightedQuery);
            String vectorStr = "[" + floatArrayToCommaString(embedding) + "]";
            int fetchLimit = limit * 3;

            String sql = """
                SELECT name, description, symptoms, precautions
                FROM diseases
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;

            List<DiseaseMatch> candidates = jdbcTemplate.query(sql,
                (rs, rowNum) -> new DiseaseMatch(
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("symptoms"),
                    rs.getString("precautions"),
                    0.0 // placeholder — filled in step 4
                ), vectorStr, fetchLimit);

            // Step 4 – re-rank by severity-weighted symptom overlap
            candidates.forEach(match ->
                match.setOverlapScore(
                    severityService.computeOverlapScore(symptoms, match.getSymptoms())
                )
            );
            candidates.sort(Comparator.comparingDouble(DiseaseMatch::getOverlapScore).reversed());

            // Step 5 – return top N
            return candidates.stream().limit(limit).collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Vector search failed: {}", e.getMessage(), e);
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

    @Data
    @AllArgsConstructor
    public static class DiseaseMatch {
        String name;
        String description;
        String symptoms;
        String precautions;
        double overlapScore;
    }
}
