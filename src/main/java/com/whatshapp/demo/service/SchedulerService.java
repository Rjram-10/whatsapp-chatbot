package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final DiseaseAlertService diseaseService;

    // Every Monday 9 AM
    @Scheduled(cron = "0 0 9 ? * MON")
    public void fetchWeeklyData() {
        System.out.println("Fetching IDSP weekly data...");
        try {
            diseaseService.updateDatabase();
        } catch (Exception e) {
            System.err.println("Failed to fetch IDSP data: " + e.getMessage());
        }
    }
}
