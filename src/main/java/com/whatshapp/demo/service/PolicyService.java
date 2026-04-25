package com.whatshapp.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.location.LocationClient;
import software.amazon.awssdk.services.location.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyService {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.accessKeyId}")
    private String accessKeyId;

    @Value("${aws.secretAccessKey}")
    private String secretKey;

    @Value("${aws.location.placeIndex}")
    private String placeIndex;

    private final WebClient geminiClient = WebClient.builder()
            .baseUrl("https://generativelanguage.googleapis.com")
            .build();

    // Cache responses to reduce API costs and improve speed
    @Cacheable(value = "policies", key = "#city + '-' + #lang")
    public String getPolicies(String city, String lang) {
        log.info("Fetching health policies for city: {}, lang: {}", city, lang);
        
        // Step 1 — Get state from city
        String state = getStateFromCity(city);
        String stateDisplay = state != null ? state : ("hi".equals(lang) ? "aapka rajya" : "your state");

        // Step 2 — Get central + state schemes
        String centralSchemes = getCentralSchemes(lang);
        String stateSchemes = getStateSchemes(state, city, lang);

        if ("hi".equals(lang)) {
            return String.format("""
                *%s ke liye sarkari swasthya yojnayen* 🏥

                *Kendriya Yojnayen (All India):*
                %s

                *%s ki Rajya Yojnayen:*
                %s

                _(Zyada jaankari ke liye: nhp.gov.in)_""",
                city, centralSchemes, stateDisplay, stateSchemes);
        }

        return String.format("""
            *Government health schemes for %s* 🏥

            *Central Government schemes (All India):*
            %s

            *%s state schemes:*
            %s

            _(For more info visit: nhp.gov.in)_""",
            city, centralSchemes, stateDisplay, stateSchemes);
    }

    private String getCentralSchemes(String lang) {
        if ("hi".equals(lang)) {
            return """
                🔹 *Ayushman Bharat PM-JAY*
                   ₹5 lakh tak muft ilaaj, 50 crore log covered.
                🔹 *PM Matru Vandana Yojana*
                   Pregnant mahilaon ke liye ₹6,000 saahayata.
                🔹 *National Health Mission (NHM)*
                   Sarkari aspatalon mein muft dawayein aur jaanch.""";
        }
        return """
            🔹 *Ayushman Bharat PM-JAY*
               Free treatment up to ₹5 lakhs, covers 50 crore people.
            🔹 *PM Matru Vandana Yojana*
               ₹6,000 cash benefit for pregnant women.
            🔹 *National Health Mission (NHM)*
               Free medicines and diagnostics at govt hospitals.""";
    }

    private String getStateSchemes(String state, String city, String lang) {
        if (state == null) return getGenericStateMessage(lang);

        // Try Gemini for dynamic state schemes
        try {
            String schemes = fetchStateSchemesfromGemini(state, lang);
            // Basic validation to prevent hallucinations
            if (schemes == null || schemes.contains("I don't know") || schemes.length() < 50) {
                return getStaticStateSchemes(state, lang);
            }
            return schemes;
        } catch (Exception e) {
            log.warn("Gemini failed for state schemes, using fallback: {}", e.getMessage());
            return getStaticStateSchemes(state, lang);
        }
    }

    private String fetchStateSchemesfromGemini(String state, String lang) {
        String prompt = String.format("""
                List the top 3-4 current government health schemes 
                specifically for %s state in India.
                Include scheme name, benefit amount if any, and eligibility.
                Respond in %s language only. Format as bullet points.
                If you don't know specific schemes, mention the state health department website.
                """, state, "hi".equals(lang) ? "Hindi" : "English");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", List.of(
                Map.of("parts", List.of(Map.of("text", prompt)))
        ));

        try {
            return geminiClient.post()
                    .uri("/v1beta/models/gemini-flash-latest:generateContent?key=" + geminiApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(r -> r.path("candidates").get(0)
                                .path("content").path("parts").get(0)
                                .path("text").asText())
                    .block();
        } catch (Exception e) {
            log.error("Error calling Gemini for state schemes: {}", e.getMessage());
            return null;
        }
    }

    private String getStaticStateSchemes(String state, String lang) {
        if (state == null) return getGenericStateMessage(lang);
        String stateLower = state.toLowerCase();

        if (stateLower.contains("rajasthan")) {
            return "hi".equals(lang) ?
                "🔹 *Mukhyamantri Chiranjeevi Yojana* — ₹25 lakh tak muft ilaaj\n🔹 *Mukhyamantri Free Dawa Yojana* — sarkari aspatalon mein muft dawayein" :
                "🔹 *Mukhyamantri Chiranjeevi Yojana* — Free treatment up to ₹25 lakhs\n🔹 *Mukhyamantri Free Dawa Yojana* — Free medicines at govt hospitals";
        }
        if (stateLower.contains("delhi")) {
            return "hi".equals(lang) ?
                "🔹 *Delhi Arogya Kosh* — Muft ilaaj BPL parivaaron ke liye\n🔹 *Mohalla Clinic* — Paas mein muft OPD aur jaanch" :
                "🔹 *Delhi Arogya Kosh* — Free treatment for BPL families\n🔹 *Mohalla Clinic* — Free OPD and tests nearby";
        }
        // Simplified fallbacks for major states...
        return getGenericStateMessage(lang);
    }

    private String getGenericStateMessage(String lang) {
        return "hi".equals(lang)
                ? "🔹 Apne rajya ki yojnaon ke liye state health department ki website dekhein.\n🔹 Helpline: 104 (Health Helpline)."
                : "🔹 Visit your state health department website for local schemes.\n🔹 Helpline: 104 (Health Helpline).";
    }

    private String getStateFromCity(String city) {
        try (LocationClient client = LocationClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretKey)))
                .build()) {

            SearchPlaceIndexForTextRequest request = SearchPlaceIndexForTextRequest.builder()
                    .indexName(placeIndex)
                    .text(city + " India")
                    .maxResults(1)
                    .filterCountries("IND")
                    .build();

            SearchPlaceIndexForTextResponse response = client.searchPlaceIndexForText(request);

            if (!response.results().isEmpty()) {
                Place place = response.results().get(0).place();
                return place.region();
            }
        } catch (Exception e) {
            log.warn("AWS Location failed for city {}: {}", city, e.getMessage());
        }
        return null; // Fallback to generic
    }
}
