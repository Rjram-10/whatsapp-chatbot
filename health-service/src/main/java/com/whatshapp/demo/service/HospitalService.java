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
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretKey)))
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
            SearchPlaceIndexForTextResponse response = client.searchPlaceIndexForText(request);
            List<SearchForTextResult> results = response.results();
            StringBuilder sb = new StringBuilder();
            sb.append("hi".equals(lang) ? "*" + city + " mein sarkari hospitals:*\n\n" : "*Government hospitals near " + city + ":*\n\n");
            int count = 0;
            for (SearchForTextResult result : results) {
                Place place = result.place();
                if (isGovtHospital(place.label())) {
                    String name = extractName(place.label());
                    String address = (place.street() != null ? place.street() + ", " : "") + (place.municipality() != null ? place.municipality() : "");
                    Double lon = place.geometry() != null ? place.geometry().point().get(0) : null;
                    Double lat = place.geometry() != null ? place.geometry().point().get(1) : null;
                    String mapsLink = (lat != null && lon != null) ? "https://maps.google.com/?q=" + lat + "," + lon : "";
                    sb.append(String.format("*%d. %s*\n%s\n%s\n\n", ++count, name, address.trim(), mapsLink));
                    if (count == 3) break;
                }
            }
            return count == 0 ? ("hi".equals(lang) ? "Aapke area mein koi sarkari hospital nahi mila." : "No government hospitals found near " + city + ".") : sb.toString();
        } catch (Exception e) {
            log.error("Hospital search error: ", e);
            return "Error fetching hospitals.";
        }
    }

    public String findNearbyByCoords(double latitude, double longitude, String lang) {
        try (LocationClient client = buildClient()) {
            SearchPlaceIndexForPositionRequest reverseRequest = SearchPlaceIndexForPositionRequest.builder()
                            .indexName(placeIndex)
                            .position(longitude, latitude)
                            .maxResults(1)
                            .build();
            SearchPlaceIndexForPositionResponse reverseResponse = client.searchPlaceIndexForPosition(reverseRequest);
            String city = "your area";
            if (!reverseResponse.results().isEmpty()) {
                Place place = reverseResponse.results().get(0).place();
                city = place.municipality() != null ? place.municipality() : (place.subRegion() != null ? place.subRegion() : (place.region() != null ? place.region() : city));
            }
            List<SearchForTextResult> results = searchWithBBox(client, city, longitude, latitude, 0.225);
            if (results.isEmpty()) results = searchWithBBox(client, city, longitude, latitude, 0.45);
            
            StringBuilder sb = new StringBuilder();
            sb.append("hi".equals(lang) ? "*Aapke nazdeek sarkari hospitals:*\n\n" : "*Government hospitals near you:*\n\n");
            int count = 0;
            for (SearchForTextResult result : results) {
                if (isGovtHospital(result.place().label())) {
                    Place place = result.place();
                    String name = extractName(place.label());
                    String address = (place.street() != null ? place.street() + ", " : "") + (place.municipality() != null ? place.municipality() + ", " : "") + (place.region() != null ? place.region() : "");
                    address = address.replaceAll(",\\s*$", "").trim();
                    Double placeLon = place.geometry() != null ? place.geometry().point().get(0) : null;
                    Double placeLat = place.geometry() != null ? place.geometry().point().get(1) : null;
                    String distanceStr = (placeLat != null && placeLon != null) ? String.format("%.1f km away", calculateDistance(latitude, longitude, placeLat, placeLon)) : "";
                    String mapsLink = (placeLat != null && placeLon != null) ? "https://maps.google.com/?q=" + placeLat + "," + placeLon : "";
                    sb.append(String.format("*%d. %s*\n%s\n%s\n%s\n\n", ++count, name, address, distanceStr, mapsLink));
                    if (count == 3) break;
                }
            }
            return count == 0 ? ("hi".equals(lang) ? "GPS se koi sarkari hospital nahi mila." : "No government hospitals found near you.") : sb.toString();
        } catch (Exception e) {
            log.error("Hospital search error: ", e);
            return "Error fetching hospitals.";
        }
    }

    private List<SearchForTextResult> searchWithBBox(LocationClient client, String city, double longitude, double latitude, double delta) {
        try {
            SearchPlaceIndexForTextRequest request = SearchPlaceIndexForTextRequest.builder()
                    .indexName(placeIndex)
                    .text("government hospital " + city + " India")
                    .maxResults(10)
                    .filterCountries("IND")
                    .filterBBox(longitude - delta, latitude - delta, longitude + delta, latitude + delta)
                    .build();
            return client.searchPlaceIndexForText(request).results();
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private boolean isGovtHospital(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return (lower.contains("hospital") || lower.contains("health centre") || lower.contains("phc") || lower.contains("chc")) 
            && (lower.contains("civil") || lower.contains("district") || lower.contains("primary health") || lower.contains("govt") || lower.contains("government") || lower.contains("sarkari"));
    }

    private String extractName(String label) {
        if (label == null) return "Government Hospital";
        String[] parts = label.split(",");
        return parts[0].trim();
    }
}
