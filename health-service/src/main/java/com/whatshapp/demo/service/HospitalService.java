package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.location.LocationClient;
import software.amazon.awssdk.services.location.model.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HospitalService — three-layer lookup pipeline:
 *
 *  Layer 1 – PMJAY canonical list
 *    Fetches the authoritative government-empanelled hospital names for a given
 *    state + district from hospitals.pmjay.gov.in. Returns names with their 
 *    PMJAY type tag (DH / SDH / CHC / PHC).
 *
 *  Layer 2 – AWS Location geocoding
 *    For each PMJAY name that lacks coordinates, calls SearchPlaceIndexForText
 *    with the hospital name + district + state as context to resolve a lat/lon.
 *    Results are cached (Redis) so each name is geocoded only once.
 *
 *  Layer 3 – Distance ranking + WhatsApp formatting
 *    Haversine-sorts the resolved list against the caller's GPS fix and returns a
 *    WhatsApp-formatted string with name, address, distance, and maps link.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HospitalService {

    @Value("${aws.region}")
    private String region;

    @Value("${aws.accessKeyId}")
    private String accessKeyId;

    @Value("${aws.secretAccessKey}")
    private String secretKey;

    @Value("${aws.location.placeIndex}")
    private String placeIndex;

    private static final String PMJAY_BASE =
        "https://hospitals.pmjay.gov.in/Search/empanelApplicationForm.htm";

    private static final int MAX_RESULTS = 5;
    private static final double BBOX_NARROW = 0.225;
    private static final double BBOX_WIDE   = 0.45;

    public String findNearbyByCoords(double latitude, double longitude, String lang) {
        try (LocationClient client = buildClient()) {
            ReverseGeoResult rg = reverseGeocode(client, latitude, longitude);
            String cityLabel = rg.city != null ? rg.city : "your area";
            String stateLabel = rg.state;

            log.info("Reverse geocode → city={}, state={}", cityLabel, stateLabel);

            Integer pmjayStateId  = resolvePmjayStateId(stateLabel);
            List<PmjayHospital> pmjayList = fetchPmjayHospitals(pmjayStateId, null);
            log.info("PMJAY returned {} hospitals for stateId={}", pmjayList.size(), pmjayStateId);

            List<ResolvedHospital> resolved = geocodeAll(client, pmjayList, cityLabel, stateLabel);

            List<ResolvedHospital> nearby = resolved.stream()
                .filter(h -> h.lat != null && h.lon != null)
                .filter(h -> Math.abs(h.lat - latitude) < BBOX_WIDE * 2
                          && Math.abs(h.lon - longitude) < BBOX_WIDE * 2)
                .peek(h -> h.distanceKm = haversine(latitude, longitude, h.lat, h.lon))
                .sorted(Comparator.comparingDouble(h -> h.distanceKm))
                .limit(MAX_RESULTS)
                .collect(Collectors.toList());

            if (nearby.isEmpty()) {
                log.warn("PMJAY+geocode yielded 0 nearby results; falling back to AWS text search");
                return fallbackAwsSearch(client, cityLabel, latitude, longitude, lang);
            }

            return formatResults(nearby, cityLabel, lang, true);

        } catch (Exception e) {
            log.error("findNearbyByCoords error", e);
            return errorMessage(lang);
        }
    }

    public String findNearby(String city, String lang) {
        try (LocationClient client = buildClient()) {
            String stateLabel = inferStateFromCity(client, city);
            log.info("Inferred state for city '{}' → '{}'", city, stateLabel);

            Integer pmjayStateId = resolvePmjayStateId(stateLabel);
            List<PmjayHospital> pmjayList = fetchPmjayHospitals(pmjayStateId, null);
            log.info("PMJAY returned {} hospitals for stateId={}", pmjayList.size(), pmjayStateId);

            List<ResolvedHospital> resolved = geocodeAll(client, pmjayList, city, stateLabel);

            List<ResolvedHospital> topN = resolved.stream()
                .limit(MAX_RESULTS)
                .collect(Collectors.toList());

            if (topN.isEmpty()) {
                return fallbackAwsSearch(client, city, null, null, lang);
            }

            return formatResults(topN, city, lang, false);

        } catch (Exception e) {
            log.error("findNearby error", e);
            return errorMessage(lang);
        }
    }

    @Cacheable(value = "pmjayHospitals", key = "#stateId + '-' + #districtId")
    public List<PmjayHospital> fetchPmjayHospitals(Integer stateId, Integer districtId) {
        if (stateId == null) {
            log.warn("No PMJAY stateId resolved; skipping PMJAY fetch");
            return Collections.emptyList();
        }
        try {
            StringBuilder urlSb = new StringBuilder(PMJAY_BASE)
                .append("?actionVal=GETHOSPNAMESLIST")
                .append("&stateId=").append(stateId)
                .append("&empanelmentType=-1");
            if (districtId != null) {
                urlSb.append("&districtId=").append(districtId);
            }

            String raw = pmjayPost(urlSb.toString());
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
            log.info("Parsed {} unique hospitals from PMJAY", hospitals.size());
            return hospitals;

        } catch (Exception e) {
            log.error("PMJAY fetch error", e);
            return Collections.emptyList();
        }
    }

    private List<ResolvedHospital> geocodeAll(LocationClient client,
                                               List<PmjayHospital> pmjayList,
                                               String city,
                                               String state) {
        List<ResolvedHospital> out = new ArrayList<>();
        for (PmjayHospital h : pmjayList) {
            out.add(geocodeSingle(client, h, city, state));
        }
        return out;
    }

    @Cacheable(value = "geocodedHospitals", key = "#pmjay.name")
    public ResolvedHospital geocodeSingle(LocationClient client,
                                           PmjayHospital pmjay,
                                           String city,
                                           String state) {
        ResolvedHospital result = new ResolvedHospital(pmjay);
        try {
            String query = pmjay.name
                + (city != null && !city.equals("your area") ? ", " + city : "")
                + (state != null ? ", " + state : "")
                + ", India";

            SearchPlaceIndexForTextRequest req = SearchPlaceIndexForTextRequest.builder()
                .indexName(placeIndex)
                .text(query)
                .maxResults(5)
                .filterCountries("IND")
                .build();

            for (SearchForTextResult r : client.searchPlaceIndexForText(req).results()) {
                Place place = r.place();
                String label = place.label() != null ? place.label().toLowerCase() : "";

                String nameToken = extractPrimaryToken(pmjay.name);
                if (label.contains(nameToken) || label.contains("hospital")
                    || label.contains("health centre") || label.contains("dispensary")) {

                    result.resolvedName = extractName(place.label());
                    result.address      = buildAddress(place);
                    result.lat          = place.geometry() != null ? place.geometry().point().get(1) : null;
                    result.lon          = place.geometry() != null ? place.geometry().point().get(0) : null;
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Geocode failed for '{}'", pmjay.name);
        }
        return result;
    }

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
            String badge  = h.pmjay.type.badge();
            String name   = h.resolvedName != null ? h.resolvedName : h.pmjay.name;
            String addr   = h.address != null && !h.address.isBlank() ? h.address : areaLabel;

            sb.append(String.format("*%d. %s*  [%s]\n", idx, name, badge));
            sb.append(addr).append("\n");

            if (hasCoords && h.distanceKm != null) {
                String distStr = hi
                    ? String.format("📍 %.1f km door", h.distanceKm)
                    : String.format("📍 %.1f km away", h.distanceKm);
                sb.append(distStr).append("\n");
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

    private String fallbackAwsSearch(LocationClient client,
                                      String city,
                                      Double latitude,
                                      Double longitude,
                                      String lang) {
        try {
            List<SearchForTextResult> results;
            if (latitude != null && longitude != null) {
                results = searchWithBBox(client, city, longitude, latitude, BBOX_NARROW);
                if (results.isEmpty()) results = searchWithBBox(client, city, longitude, latitude, BBOX_WIDE);
            } else {
                SearchPlaceIndexForTextRequest req = SearchPlaceIndexForTextRequest.builder()
                    .indexName(placeIndex)
                    .text("government hospital " + city + " India")
                    .maxResults(10)
                    .filterCountries("IND")
                    .build();
                results = client.searchPlaceIndexForText(req).results();
            }

            boolean hi = "hi".equals(lang);
            StringBuilder sb = new StringBuilder();
            sb.append(hi ? "*🏥 Aapke nazdeek sarkari hospitals:*\n\n" : "*🏥 Government hospitals near " + city + ":*\n\n");

            int count = 0;
            for (SearchForTextResult r : results) {
                if (!isGovtHospital(r.place().label())) continue;
                Place place = r.place();
                String name    = extractName(place.label());
                String address = buildAddress(place);
                Double pLon    = place.geometry() != null ? place.geometry().point().get(0) : null;
                Double pLat    = place.geometry() != null ? place.geometry().point().get(1) : null;
                String maps    = (pLat != null && pLon != null) ? "🗺 https://maps.google.com/?q=" + pLat + "," + pLon : "";
                String dist    = (latitude != null && pLat != null && pLon != null) ? String.format("📍 %.1f km away", haversine(latitude, longitude, pLat, pLon)) : "";

                sb.append(String.format("*%d. %s*\n%s\n%s\n%s\n\n", ++count, name, address, dist, maps));
                if (count == MAX_RESULTS) break;
            }
            return count == 0 ? (hi ? "Koi sarkari hospital nahi mila." : "No government hospitals found.") : sb.toString();
        } catch (Exception e) { return errorMessage(lang); }
    }

    private LocationClient buildClient() {
        return LocationClient.builder().region(Region.of(region)).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretKey))).build();
    }

    private ReverseGeoResult reverseGeocode(LocationClient client, double latitude, double longitude) {
        ReverseGeoResult result = new ReverseGeoResult();
        try {
            SearchPlaceIndexForPositionResponse resp = client.searchPlaceIndexForPosition(SearchPlaceIndexForPositionRequest.builder().indexName(placeIndex).position(longitude, latitude).maxResults(1).build());
            if (!resp.results().isEmpty()) {
                Place p = resp.results().get(0).place();
                result.city  = p.municipality() != null ? p.municipality() : p.subRegion() != null ? p.subRegion() : p.region();
                result.state = p.region();
            }
        } catch (Exception e) {}
        return result;
    }

    private String inferStateFromCity(LocationClient client, String city) {
        try {
            List<SearchForTextResult> res = client.searchPlaceIndexForText(SearchPlaceIndexForTextRequest.builder().indexName(placeIndex).text(city + " India").maxResults(1).filterCountries("IND").build()).results();
            if (!res.isEmpty() && res.get(0).place().region() != null) return res.get(0).place().region();
        } catch (Exception e) {}
        return null;
    }

    private List<SearchForTextResult> searchWithBBox(LocationClient client, String city, double longitude, double latitude, double delta) {
        try { return client.searchPlaceIndexForText(SearchPlaceIndexForTextRequest.builder().indexName(placeIndex).text("government hospital " + city + " India").maxResults(10).filterCountries("IND").filterBBox(longitude - delta, latitude - delta, longitude + delta, latitude + delta).build()).results(); } catch (Exception e) { return Collections.emptyList(); }
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private HospitalType detectType(String name) {
        String u = name.toUpperCase();
        if (u.startsWith("DH ") || u.matches("DH\\b.*") || u.contains("DISTRICT HOSPITAL")) return HospitalType.DH;
        if (u.startsWith("SDH ") || u.matches("SDH\\b.*")) return HospitalType.SDH;
        if (u.startsWith("CHC ") || u.matches("CHC\\b.*")) return HospitalType.CHC;
        if (u.startsWith("PHC ") || u.matches("PHC\\b.*") || u.contains("PRIMARY HEALTH")) return HospitalType.PHC;
        return HospitalType.OTHER;
    }

    private Integer resolvePmjayStateId(String stateName) {
        if (stateName == null) return null;
        String s = stateName.toLowerCase();
        if (s.contains("uttar pradesh")) return 35;
        if (s.contains("maharashtra")) return 23;
        if (s.contains("bihar")) return 6;
        if (s.contains("west bengal")) return 37;
        if (s.contains("madhya pradesh")) return 22;
        if (s.contains("rajasthan")) return 30;
        if (s.contains("gujarat")) return 13;
        if (s.contains("karnataka")) return 18;
        if (s.contains("andhra")) return 2;
        if (s.contains("tamil")) return 32;
        if (s.contains("telangana")) return 33;
        if (s.contains("punjab")) return 3;
        if (s.contains("haryana")) return 14;
        if (s.contains("kerala")) return 19;
        if (s.contains("jharkhand")) return 17;
        if (s.contains("chhattisgarh")) return 8;
        if (s.contains("assam")) return 5;
        if (s.contains("odisha")) return 28;
        if (s.contains("himachal")) return 15;
        if (s.contains("uttarakhand")) return 36;
        if (s.contains("jammu")) return 16;
        if (s.contains("delhi")) return 11;
        return null;
    }

    private String buildAddress(Place place) {
        List<String> parts = new ArrayList<>();
        if (place.addressNumber() != null) parts.add(place.addressNumber());
        if (place.street() != null) parts.add(place.street());
        if (place.subMunicipality() != null) parts.add(place.subMunicipality());
        if (place.municipality() != null) parts.add(place.municipality());
        if (place.region() != null) parts.add(place.region());
        return String.join(", ", parts);
    }

    private String extractName(String label) {
        return label == null ? "Government Hospital" : label.split(",")[0].trim();
    }

    private String extractPrimaryToken(String name) {
        return name.toLowerCase().replaceFirst("^(dh|sdh|chc|phc)\\s+", "").trim();
    }

    private boolean isGovtHospital(String name) {
        if (name == null) return false;
        String l = name.toLowerCase();
        return (l.contains("hospital") || l.contains("centre") || l.contains("phc") || l.contains("chc")) && (l.contains("govt") || l.contains("sarkari") || l.contains("civil"));
    }

    private String pmjayPost(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(12_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
        conn.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setRequestProperty("Origin", "https://hospitals.pmjay.gov.in");
        conn.setRequestProperty("Referer", "https://hospitals.pmjay.gov.in/Search/empnlWorkFlow.htm");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
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

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private String errorMessage(String lang) {
        return "hi".equals(lang) ? "Error fetching hospitals." : "Error fetching hospitals.";
    }

    public enum HospitalType implements Serializable {
        DH(0, "🏥 DH"), SDH(1, "🏨 SDH"), CHC(2, "🏪 CHC"), PHC(3, "💊 PHC"), OTHER(4, "🏩 Empanelled");
        private final int o; private final String b;
        HospitalType(int o, String b) { this.o = o; this.b = b; }
        public int sortOrder() { return o; }
        public String badge() { return b; }
    }

    public static class PmjayHospital implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String name; public final HospitalType type;
        public PmjayHospital(String name, HospitalType type) { this.name = name; this.type = type; }
    }

    public static class ResolvedHospital implements Serializable {
        private static final long serialVersionUID = 1L;
        public final PmjayHospital pmjay; public String resolvedName, address; public Double lat, lon, distanceKm;
        public ResolvedHospital(PmjayHospital pmjay) { this.pmjay = pmjay; }
    }

    private static class ReverseGeoResult { String city, state; }
}
