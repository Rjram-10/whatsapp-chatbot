package com.whatshapp.demo.controller;

import com.whatshapp.demo.service.DiseaseAlertService;
import com.whatshapp.demo.service.HospitalService;
import com.whatshapp.demo.service.SymptomService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final DiseaseAlertService diseaseAlertService;
    private final HospitalService hospitalService;
    private final SymptomService symptomService;

    @GetMapping("/alerts")
    public String getAlerts(@RequestParam String district, @RequestParam String lang) {
        return diseaseAlertService.getAlerts(district, lang);
    }

    @GetMapping("/hospitals/nearby")
    public String findNearby(@RequestParam String city, @RequestParam String lang) {
        return hospitalService.findNearby(city, lang);
    }

    @GetMapping("/hospitals/nearby-coords")
    public String findNearbyByCoords(@RequestParam double lat, @RequestParam double lon, @RequestParam String lang) {
        return hospitalService.findNearbyByCoords(lat, lon, lang);
    }

    @PostMapping("/symptoms/start")
    public String startSymptomChat(@RequestParam String phone, @RequestParam String lang) {
        return symptomService.startChat(phone, lang);
    }

    @PostMapping("/symptoms/continue")
    public String continueSymptomChat(@RequestParam String phone, @RequestParam String message, @RequestBody com.whatshapp.demo.model.UserSession session) {
        return symptomService.continueChat(phone, message, session);
    }
}
