package com.whatshapp.demo.service;

import com.whatshapp.demo.model.DiseaseAlert;
import com.whatshapp.demo.repo.DiseaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiseaseAlertService {

    private final DiseaseRepository repo;
    private final PythonClientService pythonClient;

    /**
     * Scheduled task to fetch fresh disease alert data from the Python service
     * and update the local database. Runs every 6 hours.
     */
    @Scheduled(fixedRate = 21600000)
    public void updateDatabase() {
        log.info("Starting scheduled disease alert fetch from Python service...");
        try {
            Map<String, List<Map<String, String>>> data = pythonClient.fetchData();
            if (data == null || data.isEmpty()) {
                log.warn("No disease alert data received from Python service.");
                return;
            }

            repo.deleteAll();
            data.forEach((district, list) -> {
                for (Map<String, String> item : list) {
                    DiseaseAlert alert = new DiseaseAlert();
                    alert.setDistrict(district);
                    alert.setDisease(item.get("disease"));
                    alert.setCases(item.get("cases"));
                    alert.setSummary(item.get("summary"));
                    repo.save(alert);
                }
            });
            log.info("Disease alert database successfully updated.");
        } catch (Exception e) {
            log.error("Failed to update disease alert database: {}", e.getMessage());
        }
    }

    public String getAlerts(String district, String lang) {
        List<DiseaseAlert> alerts = repo.findByDistrictIgnoreCase(district);
        if (alerts.isEmpty()) {
            if ("hi".equals(lang)) {
                return district + " mein abhi koi khaas bimari ka alert nahi hai. Machharon se bachen.";
            }
            return "No specific disease alerts found for " + district + " at this moment. Stay safe!";
        }
        StringBuilder sb = new StringBuilder();
        if ("hi".equals(lang)) {
            sb.append("*").append(district).append(" mein bimari ke alerts*\n\n");
        } else {
            sb.append("*Disease alerts in ").append(district).append("*\n\n");
        }
        for (DiseaseAlert a : alerts) {
            sb.append("• ").append(a.getDisease())
              .append(" - ").append(a.getCases()).append("\n")
              .append(a.getSummary()).append("\n\n");
        }
        return sb.toString();
    }
}
