package com.whatshapp.demo.service;

import com.whatshapp.demo.service.HospitalService.PmjayHospital;
import com.whatshapp.demo.service.HospitalService.ResolvedHospital;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.location.LocationClient;
import software.amazon.awssdk.services.location.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GeocodeCacheService {

    @Value("${aws.location.placeIndex}")
    private String placeIndex;

    private final LocationClient locationClient;

    public GeocodeCacheService(LocationClient locationClient) {
        this.locationClient = locationClient;
    }

    /**
     * Geocodes a single PMJAY hospital name.
     * This is in a separate service to fix the @Cacheable self-invocation bug.
     */
    @Cacheable(value = "geocodedHospitals", 
               key = "#pmjay.name + '-' + (#userLat != null ? T(Math).round(#userLat * 10) : 0) + '-' + (#userLon != null ? T(Math).round(#userLon * 10) : 0)")
    public ResolvedHospital geocodeSingle(PmjayHospital pmjay,
                                           String city,
                                           String state,
                                           Double userLat,
                                           Double userLon) {
        log.info("geocodeSingle called, placeIndex='{}', pmjay='{}'", placeIndex, pmjay.name);
        ResolvedHospital result = new ResolvedHospital(pmjay);
        try {
            String query = pmjay.name
                + (city != null && !city.equals("your area") ? ", " + city : "")
                + (state != null ? ", " + state : "")
                + ", India";

            SearchPlaceIndexForTextRequest.Builder builder = SearchPlaceIndexForTextRequest.builder()
                .indexName(placeIndex)
                .text(query)
                .maxResults(3)
                .filterCountries("IND");

            if (userLat != null && userLon != null) {
                builder.biasPosition(userLon, userLat);
            }

            for (SearchForTextResult r : this.locationClient.searchPlaceIndexForText(builder.build()).results()) {
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
            log.warn("Geocode failed for '{}'", pmjay.name, e);
        }
        return result;
    }

    private String buildAddress(Place place) {
        List<String> parts = new ArrayList<>();
        if (place.addressNumber() != null) parts.add(place.addressNumber());
        if (place.street() != null) parts.add(place.street());
        if (place.subMunicipality() != null) parts.add(place.subMunicipality());
        if (place.municipality() != null) parts.add(place.municipality());
        if (place.region() != null) parts.add(place.region());
        return parts.stream()
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.joining(", "));
    }

    private String extractName(String label) {
        if (label == null) return "Government Hospital";
        return label.split(",")[0].trim();
    }

    private String extractPrimaryToken(String name) {
        return name.toLowerCase().replaceFirst("^(dh|sdh|chc|phc)\\s+", "").trim();
    }
}
