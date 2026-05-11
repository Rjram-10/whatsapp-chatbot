package com.whatshapp.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PolicyService {

    private final String region;
    private final String accessKeyId;
    private final String secretKey;
    private final String placeIndex;
    private final String mySchemeApiKey;
    private final WebClient mySchemeClient;

    public PolicyService(
            @Value("${aws.region}") String region,
            @Value("${aws.accessKeyId}") String accessKeyId,
            @Value("${aws.secretAccessKey}") String secretKey,
            @Value("${aws.location.placeIndex}") String placeIndex,
            @Value("${myscheme.api.key}") String mySchemeApiKey) {
        this.region = region;
        this.accessKeyId = accessKeyId;
        this.secretKey = secretKey;
        this.placeIndex = placeIndex;
        this.mySchemeApiKey = mySchemeApiKey;
        this.mySchemeClient = WebClient.builder()
                .baseUrl("https://api.myscheme.gov.in")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("accept-language", "en-US,en;q=0.9,hi;q=0.8")
                .defaultHeader("origin", "https://www.myscheme.gov.in")
                .defaultHeader("referer", "https://www.myscheme.gov.in/")
                .defaultHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
                .defaultHeader("x-api-key", mySchemeApiKey)
                .defaultHeader("sec-fetch-dest", "empty")
                .defaultHeader("sec-fetch-mode", "cors")
                .defaultHeader("sec-fetch-site", "same-site")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }

    @Cacheable(value = "policies_v3", key = "#city + '-' + #lang")
    public String getPolicies(String city, String lang) {
        // Step 1 — Get state from city using AWS Location
        String state = getStateFromCity(city);
        String stateDisplay = state != null ? state : city;

        // Step 2 — Fetch health schemes from myScheme API
        List<SchemeItem> centralSchemes = fetchSchemes(null, "Central", lang);
        List<SchemeItem> stateSchemes = state != null
                ? fetchSchemes(state, "State", lang)
                : new ArrayList<>();

        // Step 3 — Build response
        return buildResponse(city, stateDisplay, centralSchemes, stateSchemes, lang);
    }

    private List<SchemeItem> fetchSchemes(String state, String level, String lang) {
        try {
            // Build the q filter array as plain string
            String qParam;
            if (state != null) {
                qParam = "[{\"identifier\":\"schemeCategory\",\"value\":\"Health & Wellness\"}," +
                         "{\"identifier\":\"level\",\"value\":\"" + level + "\"}," +
                         "{\"identifier\":\"beneficiaryState\",\"value\":\"" + state + "\"}]";
            } else {
                qParam = "[{\"identifier\":\"schemeCategory\",\"value\":\"Health & Wellness\"}," +
                         "{\"identifier\":\"level\",\"value\":\"Central\"}]";
            }

            // Manually encode the full URL — avoid Spring URI template parsing
            String encodedQ = URLEncoder.encode(qParam, StandardCharsets.UTF_8).replace("+", "%20");
            String fullUrl = "https://api.myscheme.gov.in/search/v6/schemes"
                    + "?lang=en"
                    + "&q=" + encodedQ
                    + "&keyword="
                    + "&sort=multiple_sort"
                    + "&from=0"
                    + "&size=5";

            JsonNode response = mySchemeClient.get()
                    .uri(java.net.URI.create(fullUrl)) // use URI directly, no template parsing
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            List<SchemeItem> schemes = new ArrayList<>();

            if (response != null && "Success".equals(response.path("status").asText())) {
                JsonNode items = response.path("data").path("hits").path("items");
                for (JsonNode item : items) {
                    JsonNode fields = item.path("fields");
                    String name = fields.path("schemeName").asText();
                    String shortTitle = fields.path("schemeShortTitle").asText();
                    String description = fields.path("briefDescription").asText();
                    String slug = fields.path("slug").asText();

                    List<String> tags = new ArrayList<>();
                    for (JsonNode tag : fields.path("tags")) {
                        tags.add(tag.asText());
                    }

                    schemes.add(new SchemeItem(name, shortTitle, description, slug, tags));
                }
                log.info("Fetched {} {} health schemes for state: {}",
                        schemes.size(), level, state);
            }

            return schemes;

        } catch (Exception e) {
            log.error("MyScheme API error for state {}: {}", state, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String buildResponse(String city, String state,
                                  List<SchemeItem> central,
                                  List<SchemeItem> stateSchemes,
                                  String lang) {
        StringBuilder sb = new StringBuilder();

        if ("hi".equals(lang)) {
            sb.append(String.format("*%s ke liye sarkari swasthya yojnayen* 🏥\n\n", city));
        } else {
            sb.append(String.format("*Government health schemes for %s* 🏥\n\n", city));
        }

        // Central schemes
        if (!central.isEmpty()) {
            sb.append("hi".equals(lang)
                    ? "*🇮🇳 Kendriya Yojnayen:*\n\n"
                    : "*🇮🇳 Central Government Schemes:*\n\n");

            for (SchemeItem scheme : central) {
                appendScheme(sb, scheme, lang);
            }
        }

        // State schemes
        if (!stateSchemes.isEmpty()) {
            sb.append("hi".equals(lang)
                    ? String.format("*🏛️ %s ki Rajya Yojnayen:*\n\n", state)
                    : String.format("*🏛️ %s State Schemes:*\n\n", state));

            for (SchemeItem scheme : stateSchemes) {
                appendScheme(sb, scheme, lang);
            }
        }

        // If nothing found
        if (central.isEmpty() && stateSchemes.isEmpty()) {
            sb.append("hi".equals(lang)
                    ? "Abhi koi yojna nahi mili. Baad mein try karein ya nhp.gov.in dekhein."
                    : "No schemes found right now. Try again later or visit nhp.gov.in");
        }

        // Footer — with state-specific link
        String stateUrlName = state.replace(" ", "%20");
        sb.append("\n").append("hi".equals(lang)
                ? String.format("_(Poori list: myscheme.gov.in/search/state/%s )_", stateUrlName)
                : String.format("_(Full list: myscheme.gov.in/search/state/%s )_", stateUrlName));

        return sb.toString();
    }

    private void appendScheme(StringBuilder sb, SchemeItem scheme, String lang) {
        // Trim names to fix WhatsApp bold formatting issues
        String name = scheme.name().trim();
        String shortTitle = scheme.shortTitle().trim();
        
        sb.append(String.format("🔹 *%s*", name));
        if (!shortTitle.isEmpty()) {
            sb.append(String.format(" (%s)", shortTitle));
        }
        sb.append("\n");

        String desc = scheme.description().trim();
        // Try to end at the first sentence if it's not too long (max 160 chars)
        int firstPeriod = desc.indexOf('.');
        if (firstPeriod > 20 && firstPeriod < 160) {
            desc = desc.substring(0, firstPeriod + 1);
        } else if (desc.length() > 140) {
            // Otherwise, truncate at a word boundary
            int lastSpace = desc.lastIndexOf(' ', 137);
            desc = (lastSpace > 100) ? desc.substring(0, lastSpace) + "..." : desc.substring(0, 137) + "...";
        }
        
        sb.append(desc).append("\n");

        // Tags as eligibility hints
        if (!scheme.tags().isEmpty()) {
            String tagStr = String.join(", ", scheme.tags().subList(
                    0, Math.min(3, scheme.tags().size())));
            sb.append("hi".equals(lang)
                    ? String.format("   _Yojna ke liye: %s_\n", tagStr)
                    : String.format("   _Eligibility: %s_\n", tagStr));
        }

        // Link to full scheme with link icon
        sb.append(String.format("   🔗 myscheme.gov.in/schemes/%s\n\n", scheme.slug()));
    }

    private String getStateFromCity(String city) {
        try (LocationClient client = LocationClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretKey)))
                .build()) {

            SearchPlaceIndexForTextRequest request = SearchPlaceIndexForTextRequest.builder()
                    .indexName(placeIndex)
                    .text(city + " India")
                    .maxResults(1)
                    .filterCountries("IND")
                    .build();

            SearchPlaceIndexForTextResponse response =
                    client.searchPlaceIndexForText(request);

            if (!response.results().isEmpty()) {
                String state = response.results().get(0).place().region();
                log.info("Resolved city '{}' to state '{}'", city, state);
                return state;
            }
        } catch (Exception e) {
            log.warn("Could not get state for city {}: {}", city, e.getMessage());
        }
        return null;
    }

    record SchemeItem(
            String name,
            String shortTitle,
            String description,
            String slug,
            List<String> tags
    ) {}
}
