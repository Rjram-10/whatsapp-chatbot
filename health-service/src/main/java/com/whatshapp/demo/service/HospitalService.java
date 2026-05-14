package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.location.LocationClient;
import software.amazon.awssdk.services.location.model.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * HospitalService — Optimized three-layer lookup pipeline:
 *
 *  Layer 1 – PMJAY canonical list (Fetched directly with browser-simulation)
 *  Layer 2 – Batched Geocoding (throttled to avoid AWS TPS limits)
 *  Layer 3 – Distance ranking (Haversine) + WhatsApp formatting
 */
@Slf4j
@Service
public class HospitalService {

    private final LocationClient locationClient;
    private final GeocodeCacheService geocodeCacheService;
    private final Executor geocodeExecutor;

    public HospitalService(LocationClient locationClient,
                           GeocodeCacheService geocodeCacheService,
                           @Qualifier("geocodeExecutor") Executor geocodeExecutor) {
        this.locationClient = locationClient;
        this.geocodeCacheService = geocodeCacheService;
        this.geocodeExecutor = geocodeExecutor;
    }

    @Value("${aws.location.placeIndex}")
    private String placeIndex;

    private static final String PMJAY_BASE =
        "https://hospitals.pmjay.gov.in/Search/empanelApplicationForm.htm";

    private static final int MAX_RESULTS      = 5;
    private static final int CANDIDATE_LIMIT  = 15;  // FIX: reduced from 30 — fewer AWS calls
    private static final int GEOCODE_BATCH    = 5;   // FIX: fire at most 5 AWS calls at a time
    private static final long BATCH_DELAY_MS  = 200; // FIX: 200ms pause between batches
    private static final int  FUTURE_TIMEOUT  = 8;   // FIX: increased from 5s to 8s per future
    private static final double BBOX_NARROW   = 0.225;
    private static final double BBOX_WIDE     = 0.45;

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    public String findNearbyByCoords(double latitude, double longitude, String lang) {
        try {
            // Step 1 – reverse geocode to get city + state
            ReverseGeoResult rg = reverseGeocode(locationClient, latitude, longitude);
            String cityLabel  = rg.city  != null ? rg.city  : "your area";
            String stateLabel = rg.state;
            log.info("Reverse geocode → city={}, state={}", cityLabel, stateLabel);

            // Step 2 – resolve PMJAY state ID
            Integer pmjayStateId = resolvePmjayStateId(stateLabel);

            // Step 3 – PMJAY canonical list
            List<PmjayHospital> pmjayList = fetchPmjayHospitals(pmjayStateId, null);
            log.info("PMJAY returned {} hospitals for stateId={}", pmjayList.size(), pmjayStateId);

            // Step 4 – Pre-filter by city name to prefer local hospitals
            List<PmjayHospital> candidates = selectCandidates(pmjayList, cityLabel);
            log.info("Candidates after city pre-filter: {}", candidates.size());

            // Step 5 – Batched geocode (throttled)
            List<ResolvedHospital> resolved = geocodeAllBatched(
                locationClient, candidates, cityLabel, stateLabel, latitude, longitude);

            // Step 6 – filter by bounding box, sort by distance, take top N
            List<ResolvedHospital> nearby = resolved.stream()
                .filter(h -> h.lat != null && h.lon != null)
                .filter(h -> Math.abs(h.lat - latitude) < BBOX_WIDE * 2
                          && Math.abs(h.lon - longitude) < BBOX_WIDE * 2)
                .peek(h -> h.distanceKm = haversine(latitude, longitude, h.lat, h.lon))
                .sorted(Comparator.comparingDouble(h -> h.distanceKm))
                .limit(MAX_RESULTS)
                .collect(Collectors.toList());

            if (nearby.isEmpty()) {
                log.info("No geocoded results within bbox — using AWS fallback");
                return fallbackAwsSearch(locationClient, cityLabel, latitude, longitude, lang);
            }

            return formatResults(nearby, cityLabel, lang, true);

        } catch (Exception e) {
            log.error("findNearbyByCoords error", e);
            return errorMessage(lang);
        }
    }

    public String findNearby(String city, String lang) {
        try {
            // Step 1 – infer state from city name
            String stateLabel = inferStateFromCity(locationClient, city);
            log.info("Inferred state for city '{}' → '{}'", city, stateLabel);

            // Step 2 – geocode the city itself to get a coordinate anchor
            Double[] center  = geocodeCity(locationClient, city);
            Double cityLat   = center != null ? center[0] : null;
            Double cityLon   = center != null ? center[1] : null;

            // Step 3 – PMJAY list
            Integer pmjayStateId = resolvePmjayStateId(stateLabel);
            List<PmjayHospital> pmjayList = fetchPmjayHospitals(pmjayStateId, null);

            // Step 4 – Pre-filter by city name
            List<PmjayHospital> candidates = selectCandidates(pmjayList, city);
            log.info("Candidates after city pre-filter: {}", candidates.size());

            // Step 5 – Batched geocode (throttled)
            List<ResolvedHospital> resolved = geocodeAllBatched(
                locationClient, candidates, city, stateLabel, cityLat, cityLon);

            // Step 6 – sort by distance if we have a center point
            List<ResolvedHospital> topN = resolved.stream()
                .filter(h -> h.lat != null && h.lon != null)
                .peek(h -> {
                    if (cityLat != null && cityLon != null)
                        h.distanceKm = haversine(cityLat, cityLon, h.lat, h.lon);
                })
                .sorted(Comparator.comparingDouble(h -> h.distanceKm != null ? h.distanceKm : Double.MAX_VALUE))
                .limit(MAX_RESULTS)
                .collect(Collectors.toList());

            if (topN.isEmpty()) {
                log.info("No geocoded results for city '{}' — using AWS fallback", city);
                return fallbackAwsSearch(locationClient, city, cityLat, cityLon, lang);
            }

            return formatResults(topN, city, lang, cityLat != null);

        } catch (Exception e) {
            log.error("findNearby error", e);
            return errorMessage(lang);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CANDIDATE SELECTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prefer hospitals whose name contains the city. Fall back to the full list.
     * Always caps at CANDIDATE_LIMIT to protect against AWS TPS throttling.
     */
    private List<PmjayHospital> selectCandidates(List<PmjayHospital> pmjayList, String city) {
        String cityLower = city.toLowerCase();
        List<PmjayHospital> cityMatches = pmjayList.stream()
            .filter(h -> h.name.toLowerCase().contains(cityLower))
            .collect(Collectors.toList());

        List<PmjayHospital> source = cityMatches.size() >= 5 ? cityMatches : pmjayList;
        return source.stream().limit(CANDIDATE_LIMIT).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BATCHED GEOCODING  ← core fix for AWS TPS throttling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fires geocoding in small batches (GEOCODE_BATCH at a time) with a short
     * pause between batches so we never exceed AWS Location's TPS limit.
     *
     * Previously all 30 futures were submitted at once, causing AWS to throttle
     * every single request, which caused all f.get(5s) calls to time out.
     */
    private List<ResolvedHospital> geocodeAllBatched(LocationClient client,
                                                      List<PmjayHospital> hospitals,
                                                      String city,
                                                      String state,
                                                      Double userLat,
                                                      Double userLon) {
        List<ResolvedHospital> results = new ArrayList<>();

        // Partition into batches of GEOCODE_BATCH
        for (int i = 0; i < hospitals.size(); i += GEOCODE_BATCH) {
            List<PmjayHospital> batch = hospitals.subList(i, Math.min(i + GEOCODE_BATCH, hospitals.size()));

            // Submit this batch in parallel
            List<CompletableFuture<ResolvedHospital>> futures = batch.stream()
                .map(h -> CompletableFuture.supplyAsync(
                    () -> geocodeCacheService.geocodeSingle(h, city, state, userLat, userLon),
                    geocodeExecutor
                ))
                .collect(Collectors.toList());

            // Collect batch results — increase timeout to 8s to survive slow AWS responses
            for (CompletableFuture<ResolvedHospital> f : futures) {
                try {
                    ResolvedHospital r = f.get(FUTURE_TIMEOUT, TimeUnit.SECONDS);
                    if (r != null) results.add(r);
                } catch (Exception e) {
                    log.warn("Geocoding future timed out or failed for batch item: {}", e.getMessage(), e);
                }
            }

            // Stop early if we already have enough good results
            long goodSoFar = results.stream().filter(h -> h.lat != null && h.lon != null).count();
            if (goodSoFar >= MAX_RESULTS) {
                log.info("Early exit after {} geocodes — found {} resolved hospitals", results.size(), goodSoFar);
                break;
            }

            // Throttle: pause between batches to respect AWS TPS limits
            if (i + GEOCODE_BATCH < hospitals.size()) {
                try { Thread.sleep(BATCH_DELAY_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.info("Geocoded {} hospitals, {} resolved with coordinates",
            results.size(), results.stream().filter(h -> h.lat != null).count());
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PMJAY FETCH
    // ─────────────────────────────────────────────────────────────────────────

    @Cacheable(value = "pmjayHospitals", key = "#stateId + '-' + #districtId")
    public List<PmjayHospital> fetchPmjayHospitals(Integer stateId, Integer districtId) {
        if (stateId == null) return Collections.emptyList();
        try {
            String url = new StringBuilder(PMJAY_BASE)
                .append("?actionVal=GETHOSPNAMESLIST")
                .append("&stateId=").append(stateId)
                .append("&empanelmentType=-1")
                .toString();

            String raw = pmjayPost(url);
            raw = raw.trim().replaceAll("^\\[|]$", "");

            List<PmjayHospital> hospitals = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            for (String token : raw.split(",")) {
                String[] parts = token.trim().split("~");
                String name = (parts.length >= 2 ? parts[1] : parts[0]).trim();
                if (name.isEmpty() || seen.contains(name.toLowerCase())) continue;
                seen.add(name.toLowerCase());
                hospitals.add(new PmjayHospital(name, detectType(name)));
            }

            hospitals.sort(Comparator.comparingInt(h -> h.type.sortOrder()));
            log.info("Fetched {} unique hospitals from PMJAY for stateId={}", hospitals.size(), stateId);
            return hospitals;

        } catch (Exception e) {
            log.error("PMJAY fetch error for stateId={}: {}", stateId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FORMATTING
    // ─────────────────────────────────────────────────────────────────────────

    private String formatResults(List<ResolvedHospital> hospitals,
                                  String areaLabel,
                                  String lang,
                                  boolean hasCoords) {
        boolean hi = "hi".equals(lang);
        StringBuilder sb = new StringBuilder();

        if (hi) {
            sb.append(hasCoords
                ? "*🏥 Aapke nazdeek PMJAY empanelled hospitals:*\n\n"
                : "*🏥 " + areaLabel + " mein PMJAY empanelled hospitals:*\n\n");
        } else {
            sb.append(hasCoords
                ? "*🏥 PMJAY-empanelled hospitals near you:*\n\n"
                : "*🏥 PMJAY-empanelled hospitals in " + areaLabel + ":*\n\n");
        }

        int idx = 0;
        for (ResolvedHospital h : hospitals) {
            idx++;
            String badge = h.pmjay.type.badge();
            String name  = h.resolvedName != null ? h.resolvedName : h.pmjay.name;
            String addr  = h.address != null && !h.address.isBlank() ? h.address : areaLabel;

            sb.append(String.format("*%d. %s*  [%s]\n", idx, name, badge));
            sb.append(addr).append("\n");

            if (hasCoords && h.distanceKm != null) {
                sb.append(hi
                    ? String.format("📍 %.1f km door\n", h.distanceKm)
                    : String.format("📍 %.1f km away\n", h.distanceKm));
            }

            if (h.lat != null && h.lon != null) {
                sb.append("🗺 https://maps.google.com/?q=")
                  .append(h.lat).append(",").append(h.lon).append("\n");
            }
            sb.append("\n");
        }

        sb.append("_Source: PMJAY empanelled list · Ayushman Bharat_");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FALLBACK: AWS Location direct search
    // ─────────────────────────────────────────────────────────────────────────

    private String fallbackAwsSearch(LocationClient client, String city,
                                      Double latitude, Double longitude, String lang) {
        try {
            List<SearchForTextResult> results;
            if (latitude != null && longitude != null) {
                results = searchWithBBox(client, city, longitude, latitude, BBOX_NARROW);
                if (results.isEmpty())
                    results = searchWithBBox(client, city, longitude, latitude, BBOX_WIDE);
            } else {
                results = client.searchPlaceIndexForText(
                    SearchPlaceIndexForTextRequest.builder()
                        .indexName(placeIndex)
                        .text("government hospital " + city + " India")
                        .maxResults(10)
                        .filterCountries("IND")
                        .build()).results();
            }

            boolean hi = "hi".equals(lang);
            StringBuilder sb = new StringBuilder();
            sb.append(hi
                ? "*🏥 Aapke nazdeek sarkari hospitals:*\n\n"
                : "*🏥 Government hospitals near " + city + ":*\n\n");

            int count = 0;
            for (SearchForTextResult r : results) {
                Place place = r.place();
                String label = place.label() != null ? place.label() : "";

                // FIX: broadened filter — AWS labels rarely say "govt"; match on "hospital" alone
                if (!isHospitalLabel(label)) continue;

                String name  = label.split(",")[0];
                Double pLon  = place.geometry() != null ? place.geometry().point().get(0) : null;
                Double pLat  = place.geometry() != null ? place.geometry().point().get(1) : null;

                sb.append(String.format("*%d. %s*\n", ++count, name));
                if (latitude != null && pLat != null && pLon != null) {
                    sb.append(String.format("📍 %.1f km away\n", haversine(latitude, longitude, pLat, pLon)));
                }
                if (pLat != null && pLon != null) {
                    sb.append("🗺 https://maps.google.com/?q=").append(pLat).append(",").append(pLon).append("\n");
                }
                sb.append("\n");

                if (count == MAX_RESULTS) break;
            }

            if (count == 0) {
                return hi
                    ? "Maaf karein, is shehar mein koi sarkari hospital nahi mila."
                    : "Sorry, no government hospitals found near " + city + ".";
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("Fallback AWS search failed", e);
            return errorMessage(lang);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GEOCODING HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private ReverseGeoResult reverseGeocode(LocationClient client, double latitude, double longitude) {
        ReverseGeoResult result = new ReverseGeoResult();
        try {
            SearchPlaceIndexForPositionResponse resp = client.searchPlaceIndexForPosition(
                SearchPlaceIndexForPositionRequest.builder()
                    .indexName(placeIndex)
                    .position(longitude, latitude)
                    .maxResults(1)
                    .build());

            if (!resp.results().isEmpty()) {
                Place p = resp.results().get(0).place();
                result.city  = p.municipality() != null ? p.municipality()
                             : p.subRegion()    != null ? p.subRegion() : p.region();
                result.state = p.region()       != null ? p.region() : p.subRegion();
            }
        } catch (Exception e) {
            log.warn("Reverse geocode failed: {}", e.getMessage());
        }
        return result;
    }

    private String inferStateFromCity(LocationClient client, String city) {
        try {
            List<SearchForTextResult> res = client.searchPlaceIndexForText(
                SearchPlaceIndexForTextRequest.builder()
                    .indexName(placeIndex)
                    .text(city + " India")
                    .maxResults(1)
                    .filterCountries("IND")
                    .build()).results();

            if (!res.isEmpty()) {
                Place p = res.get(0).place();
                String state = p.region() != null ? p.region() : p.subRegion();
                log.info("AWS inferState for '{}' → raw_region={}, raw_subregion={}, inferred={}",
                         city, p.region(), p.subRegion(), state);
                return state;
            }
        } catch (Exception e) {
            log.warn("State inference failed for city '{}': {}", city, e.getMessage());
        }
        return null;
    }

    private Double[] geocodeCity(LocationClient client, String city) {
        try {
            List<SearchForTextResult> res = client.searchPlaceIndexForText(
                SearchPlaceIndexForTextRequest.builder()
                    .indexName(placeIndex)
                    .text(city + " India")
                    .maxResults(1)
                    .filterCountries("IND")
                    .build()).results();
            if (!res.isEmpty()) {
                List<Double> pt = res.get(0).place().geometry().point();
                return new Double[]{pt.get(1), pt.get(0)}; // [lat, lon]
            }
        } catch (Exception e) {
            log.warn("geocodeCity failed for '{}': {}", city, e.getMessage());
        }
        return null;
    }

    private List<SearchForTextResult> searchWithBBox(LocationClient client, String city,
                                                      double longitude, double latitude, double delta) {
        try {
            return client.searchPlaceIndexForText(
                SearchPlaceIndexForTextRequest.builder()
                    .indexName(placeIndex)
                    .text("government hospital " + city + " India")
                    .maxResults(10)
                    .filterCountries("IND")
                    .filterBBox(longitude - delta, latitude - delta, longitude + delta, latitude + delta)
                    .build()).results();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITY
    // ─────────────────────────────────────────────────────────────────────────

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private HospitalType detectType(String name) {
        String u = name.toUpperCase();
        if (u.startsWith("DH ") || u.matches("DH\\b.*") || u.contains("DISTRICT HOSPITAL")) return HospitalType.DH;
        if (u.startsWith("SDH ") || u.matches("SDH\\b.*"))                                   return HospitalType.SDH;
        if (u.startsWith("CHC ") || u.matches("CHC\\b.*"))                                   return HospitalType.CHC;
        if (u.startsWith("PHC ") || u.matches("PHC\\b.*") || u.contains("PRIMARY HEALTH"))   return HospitalType.PHC;
        return HospitalType.OTHER;
    }

    /**
     * FIX: broadened from isGovtHospital() — AWS Location labels don't always say "govt".
     * Match anything that looks like a medical facility.
     */
    private boolean isHospitalLabel(String name) {
        if (name == null) return false;
        String l = name.toLowerCase();
        return l.contains("hospital") || l.contains("health centre") || l.contains("dispensary")
            || l.contains("phc") || l.contains("chc") || l.contains("medical");
    }

    private Integer resolvePmjayStateId(String stateName) {
        if (stateName == null) return null;
        String s = stateName.toLowerCase();
        if (s.contains("jammu") || s.contains("kashmir")) return 1;
        if (s.contains("himachal"))        return 2;
        if (s.contains("punjab"))          return 3;
        if (s.contains("chandigarh"))      return 4;
        if (s.contains("uttarakhand"))     return 5;
        if (s.contains("haryana"))         return 6;
        if (s.contains("rajasthan"))       return 8;
        if (s.contains("uttar pradesh") || s.contains("up")) return 9;
        if (s.contains("bihar"))           return 10;
        if (s.contains("sikkim"))          return 11;
        if (s.contains("arunachal"))       return 12;
        if (s.contains("nagaland"))        return 13;
        if (s.contains("manipur"))         return 14;
        if (s.contains("mizoram"))         return 15;
        if (s.contains("tripura"))         return 16;
        if (s.contains("meghalaya"))       return 17;
        if (s.contains("assam"))           return 18;
        if (s.contains("jharkhand"))       return 20;
        if (s.contains("chhattisgarh"))    return 22;
        if (s.contains("madhya pradesh"))  return 23;
        if (s.contains("gujarat"))         return 24;
        if (s.contains("daman") || s.contains("diu"))         return 25;
        if (s.contains("dadra") || s.contains("nagar haveli")) return 26;
        if (s.contains("maharashtra"))     return 27;
        if (s.contains("andhra"))          return 28;
        if (s.contains("karnataka"))       return 29;
        if (s.contains("goa"))             return 30;
        if (s.contains("lakshadweep"))     return 31;
        if (s.contains("kerala"))          return 32;
        if (s.contains("tamil"))           return 33;
        if (s.contains("puducherry") || s.contains("pondicherry")) return 34;
        if (s.contains("andaman") || s.contains("nicobar"))        return 35;
        if (s.contains("telangana"))       return 36;
        if (s.contains("ladakh"))          return 37;
        if (s.contains("west bengal") || s.contains("odisha") || s.contains("delhi")) {
            log.info("State '{}' does not participate in PMJAY. Using fallback AWS search.", stateName);
            return null;
        }
        log.warn("No PMJAY stateId mapping found for state '{}'", stateName);
        return null;
    }

    private String pmjayPost(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(12_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
        conn.setRequestProperty("Origin", "https://hospitals.pmjay.gov.in");
        conn.setRequestProperty("Referer", "https://hospitals.pmjay.gov.in/Search/empnlWorkFlow.htm");
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.getOutputStream().close();

        int status = conn.getResponseCode();
        if (status != 200) throw new IOException("PMJAY returned HTTP " + status);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private String errorMessage(String lang) {
        return "hi".equals(lang)
            ? "Maaf karein, hospital dhundhne mein dikkat aayi. Baad mein koshish karein."
            : "Sorry, we couldn't fetch hospital information right now. Please try again.";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INNER TYPES
    // ─────────────────────────────────────────────────────────────────────────

    public enum HospitalType implements Serializable {
        DH(0, "🏥 DH"), SDH(1, "🏨 SDH"), CHC(2, "🏪 CHC"), PHC(3, "💊 PHC"), OTHER(4, "🏩 Empanelled");
        private final int o; private final String b;
        HospitalType(int o, String b) { this.o = o; this.b = b; }
        public int sortOrder() { return o; }
        public String badge()  { return b; }
    }

    public static class PmjayHospital implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final HospitalType type;
        public PmjayHospital(String name, HospitalType type) { this.name = name; this.type = type; }
    }

    public static class ResolvedHospital implements Serializable {
        private static final long serialVersionUID = 1L;
        public final PmjayHospital pmjay;
        public String resolvedName, address;
        public Double lat, lon, distanceKm;
        public ResolvedHospital(PmjayHospital pmjay) { this.pmjay = pmjay; }
    }

    private static class ReverseGeoResult { String city, state; }
}
