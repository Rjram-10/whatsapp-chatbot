package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.location.LocationClient;
import software.amazon.awssdk.services.location.model.*;

import java.util.ArrayList;
import java.util.List;

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

    private LocationClient buildClient() {
        return LocationClient.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretKey)
                        )
                )
                .build();
    }

    public String findNearby(String city, String lang) {
        try (LocationClient client = buildClient()) {

            SearchPlaceIndexForTextRequest request = SearchPlaceIndexForTextRequest.builder()
                    .indexName(placeIndex)
                    .text("government hospital " + city + " India")
                    .maxResults(10)
                    .filterCountries("IND")
                    .build();

            SearchPlaceIndexForTextResponse response =
                    client.searchPlaceIndexForText(request);

            List<SearchForTextResult> results = response.results();

            StringBuilder sb = new StringBuilder();
            sb.append("hi".equals(lang)
                    ? "*" + city + " mein sarkari hospitals:*\n\n"
                    : "*Government hospitals near " + city + ":*\n\n");

            int count = 0;
            for (SearchForTextResult result : results) {
                Place place = result.place();
                String label = place.label();
                String street = place.street() != null ? place.street() : "";
                String municipality = place.municipality() != null ? place.municipality() : "";

                if (isGovtHospital(label)) {
                    String name = extractName(label);
                    String address = "";
                    if (!street.isEmpty()) address += street + ", ";
                    if (!municipality.isEmpty()) address += municipality;

                    Double lon = place.geometry() != null ? place.geometry().point().get(0) : null;
                    Double lat = place.geometry() != null ? place.geometry().point().get(1) : null;
                    String mapsLink = (lat != null && lon != null)
                            ? "https://maps.google.com/?q=" + lat + "," + lon
                            : "";

                    sb.append(String.format("*%d. %s*\n%s\n%s\n\n",
                            ++count, name, address.trim(), mapsLink));
                    if (count == 3) break;
                }
            }

            if (count == 0) {
                return "hi".equals(lang)
                        ? "Aapke area mein koi sarkari hospital nahi mila."
                        : "No government hospitals found near " + city + ".";
            }

            return sb.toString();

        } catch (LocationException e) {
            log.error("AWS Location error in findNearby: ", e);
            return "hi".equals(lang)
                    ? "Hospital dhundne mein takleef aayi. Baad mein try karein."
                    : "Could not fetch hospitals right now. Please try again later.";
        }
    }

    public String findNearbyByCoords(double latitude, double longitude, String lang) {
        try (LocationClient client = buildClient()) {

            // Step 1 — Reverse geocode to get city name
            SearchPlaceIndexForPositionRequest reverseRequest =
                    SearchPlaceIndexForPositionRequest.builder()
                            .indexName(placeIndex)
                            .position(longitude, latitude)
                            .maxResults(1)
                            .build();

            SearchPlaceIndexForPositionResponse reverseResponse =
                    client.searchPlaceIndexForPosition(reverseRequest);

            String city = "your area";
            if (!reverseResponse.results().isEmpty()) {
                Place place = reverseResponse.results().get(0).place();
                if (place.municipality() != null) city = place.municipality();
                else if (place.subRegion() != null) city = place.subRegion();
                else if (place.region() != null) city = place.region();
            }

            // Step 2 — Try 25km bounding box first
            List<SearchForTextResult> results = searchWithBBox(
                    client, city, longitude, latitude, 0.225);

            // Step 3 — Expand to 50km if nothing found
            if (results.isEmpty()) {
                results = searchWithBBox(client, city, longitude, latitude, 0.45);
            }

            // Step 4 — Build response
            StringBuilder sb = new StringBuilder();
            sb.append("hi".equals(lang)
                    ? "*Aapke nazdeek sarkari hospitals:*\n\n"
                    : "*Government hospitals near you:*\n\n");

            int count = 0;
            List<SearchForTextResult> govtResults = new ArrayList<>();

            // First pass — strict government filter
            for (SearchForTextResult result : results) {
                if (isGovtHospital(result.place().label())) {
                    govtResults.add(result);
                }
            }

            // Fallback — if no govt hospitals found, show any hospital nearby
            if (govtResults.isEmpty()) {
                for (SearchForTextResult result : results) {
                    String label = result.place().label();
                    if (label != null && label.toLowerCase().contains("hospital")) {
                        govtResults.add(result);
                    }
                }
            }

            for (SearchForTextResult result : govtResults) {
                Place place = result.place();
                String label = place.label();
                String name = extractName(label);
                String street = place.street() != null ? place.street() : "";
                String municipality = place.municipality() != null ? place.municipality() : "";
                String region = place.region() != null ? place.region() : "";

                String address = "";
                if (!street.isEmpty()) address += street + ", ";
                if (!municipality.isEmpty()) address += municipality + ", ";
                if (!region.isEmpty()) address += region;
                // Remove trailing comma and space
                address = address.replaceAll(",\\s*$", "").trim();

                Double placeLon = place.geometry() != null ? place.geometry().point().get(0) : null;
                Double placeLat = place.geometry() != null ? place.geometry().point().get(1) : null;

                String distanceStr = "";
                String mapsLink = "";
                if (placeLat != null && placeLon != null) {
                    double distKm = calculateDistance(latitude, longitude, placeLat, placeLon);
                    distanceStr = String.format("%.1f km away", distKm);
                    mapsLink = "https://maps.google.com/?q=" + placeLat + "," + placeLon;
                }

                sb.append(String.format("*%d. %s*\n%s\n%s\n%s\n\n",
                        ++count, name, address, distanceStr, mapsLink));

                if (count == 3) break;
            }

            if (count == 0) {
                return "hi".equals(lang)
                        ? "GPS se koi sarkari hospital nahi mila. Apna shehar type karein (jaise: " + city + ")."
                        : "No government hospitals found near you. Try typing your city name (e.g. " + city + ").";
            }

            return sb.toString();

        } catch (LocationException e) {
            log.error("AWS Location error in findNearbyByCoords: ", e);
            return "hi".equals(lang)
                    ? "Hospital dhundne mein takleef aayi. Baad mein try karein."
                    : "Could not fetch hospitals right now. Please try again later.";
        }
    }

    private List<SearchForTextResult> searchWithBBox(
            LocationClient client, String city,
            double longitude, double latitude, double delta) {

        try {
            SearchPlaceIndexForTextRequest request = SearchPlaceIndexForTextRequest.builder()
                    .indexName(placeIndex)
                    .text("government hospital " + city + " India")
                    .maxResults(10)
                    .filterCountries("IND")
                    .filterBBox(
                            longitude - delta, latitude - delta,
                            longitude + delta, latitude + delta)
                    .build();
            return client.searchPlaceIndexForText(request).results();
        } catch (Exception e) {
            log.warn("BBox search failed for delta {}: {}", delta, e.getMessage());
            return new ArrayList<>();
        }
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private boolean isGovtHospital(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        boolean hasHealthWord = lower.contains("hospital")
                || lower.contains("health centre")
                || lower.contains("health center")
                || lower.contains("phc")
                || lower.contains("chc")
                || lower.contains("dispensary");
        boolean hasGovtWord = lower.contains("civil")
                || lower.contains("district")
                || lower.contains("primary health")
                || lower.contains("govt")
                || lower.contains("government")
                || lower.contains("sarkari")
                || lower.contains("rajkiya")
                || lower.contains("community health")
                || lower.contains("esic")
                || lower.contains("railway hospital")
                || lower.contains("military hospital");
        boolean isArea = lower.endsWith("area") || lower.contains("hospital area");
        return hasHealthWord && hasGovtWord && !isArea;
    }

    private String extractName(String label) {
        if (label == null) return "Government Hospital";
        String[] parts = label.split(",");
        String name = parts[0].trim();
        if (name.equalsIgnoreCase("Government Hospital") && parts.length > 1) {
            name = name + " - " + parts[1].trim();
        }
        return name;
    }
}