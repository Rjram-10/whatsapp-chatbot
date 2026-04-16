package com.whatshapp.demo.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class DiseaseAlert {

    @Id
    @GeneratedValue
    private Long id;

    private String district;
    private String disease;
    private String cases;
    private String summary;
}
