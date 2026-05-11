package com.whatshapp.demo.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "health-service", url = "${services.health.url:http://localhost:8081}")
public interface HealthClient {

    @GetMapping("/api/health/alerts")
    String getAlerts(@RequestParam("district") String district, @RequestParam("lang") String lang);

    @GetMapping("/api/health/hospitals/nearby")
    String findNearby(@RequestParam("city") String city, @RequestParam("lang") String lang);

    @GetMapping("/api/health/hospitals/nearby-coords")
    String findNearbyByCoords(@RequestParam("lat") double lat, @RequestParam("lon") double lon, @RequestParam("lang") String lang);

    @PostMapping("/api/health/symptoms/start")
    String startSymptomChat(@RequestParam("phone") String phone, @RequestParam("lang") String lang);

    @PostMapping("/api/health/symptoms/continue")
    String continueSymptomChat(@RequestParam("phone") String phone, @RequestParam("message") String message, @RequestBody com.whatshapp.demo.model.UserSession session);
}
