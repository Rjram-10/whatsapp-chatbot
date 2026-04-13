package com.whatshapp.demo.service;

import org.springframework.stereotype.Service;

@Service
public class DiseaseAlertService {
    public String getAlerts(String city, String lang) {
        if ("hi".equals(lang)) {
            return city + " mein abhi koi khaas bimari ka alert nahi hai. Machharon se bachen.";
        }
        return "No specific disease alerts present in " + city + " right now. Make sure to prevent mosquito breeding.";
    }
}
