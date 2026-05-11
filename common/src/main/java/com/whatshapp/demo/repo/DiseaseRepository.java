package com.whatshapp.demo.repo;

import com.whatshapp.demo.model.DiseaseAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DiseaseRepository extends JpaRepository<DiseaseAlert, Long> {
    List<DiseaseAlert> findByDistrictIgnoreCase(String district);
}
