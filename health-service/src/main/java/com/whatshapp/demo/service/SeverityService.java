package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SeverityService — weights symptoms by clinical severity before embedding.
 *
 * Two purposes:
 *  1. buildWeightedQuery()    — repeats high-severity symptoms so they dominate the embedding vector.
 *  2. computeOverlapScore()   — re-ranks vector-search results by severity-weighted symptom overlap.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeverityService {

    private final JdbcTemplate jdbcTemplate;

    /** In-memory cache, loaded once on first use. */
    private volatile Map<String, Integer> severityCache = null;

    public Map<String, Integer> getSeverityWeights() {
        if (severityCache == null) {
            synchronized (this) {
                if (severityCache == null) {
                    Map<String, Integer> cache = new HashMap<>();
                    jdbcTemplate.query(
                        "SELECT symptom, weight FROM symptom_severity",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            cache.put(rs.getString("symptom"), rs.getInt("weight"))
                    );
                    severityCache = cache;
                    log.info("Loaded {} symptom severity weights", cache.size());
                }
            }
        }
        return severityCache;
    }

    /**
     * Builds a severity-weighted query string for embedding.
     * High-severity symptoms are repeated so they carry more weight in the vector space.
     *
     * Repetition scale: weight 1-2 → 1×, 3-4 → 2×, 5-6 → 3×, 7 → 4×
     */
    public String buildWeightedQuery(List<String> symptoms) {
        Map<String, Integer> weights = getSeverityWeights();
        List<String> weighted = new ArrayList<>();

        for (String symptom : symptoms) {
            String normalized = normalize(symptom);
            int weight = weights.getOrDefault(normalized, 3); // default mid-weight
            int repetitions = Math.max(1, (weight + 1) / 2);
            for (int i = 0; i < repetitions; i++) {
                weighted.add(symptom.trim());
            }
        }

        return String.join(", ", weighted);
    }

    /**
     * Scores a retrieved disease by how well its symptoms overlap with the user's input,
     * weighted by severity. Used to re-rank vector-search candidates.
     *
     * @return 0.0 – 1.0, higher = better match
     */
    public double computeOverlapScore(String userSymptoms, String diseaseSymptoms) {
        if (userSymptoms == null || diseaseSymptoms == null) return 0.0;

        Map<String, Integer> weights = getSeverityWeights();

        Set<String> userSet = Arrays.stream(userSymptoms.split("[,\\.\\s]+"))
            .map(this::normalize)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());

        double matchScore = 0;
        double maxScore = 0;

        for (String s : userSet) {
            int w = weights.getOrDefault(s, 3);
            maxScore += w;
            if (diseaseSymptoms.toLowerCase().contains(s.replace("_", " "))) {
                matchScore += w;
            }
        }

        return maxScore > 0 ? matchScore / maxScore : 0.0;
    }

    private String normalize(String symptom) {
        return symptom.trim().toLowerCase()
            .replace(" ", "_")
            .replaceAll("[^a-z_]", "");
    }
}
