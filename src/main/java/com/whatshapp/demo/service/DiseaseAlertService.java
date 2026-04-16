package com.whatshapp.demo.service;

import com.whatshapp.demo.model.DiseaseAlert;
import com.whatshapp.demo.repo.DiseaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DiseaseAlertService {

    private final DiseaseRepository repo;
    private final PythonClientService pythonClient;

    public void updateDatabase() {
        Map<String, List<Map<String, String>>> data = pythonClient.fetchData();

        if (data == null) return;

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
