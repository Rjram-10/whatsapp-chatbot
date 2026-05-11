package com.whatshapp.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PythonClientService {

    private final WebClient webClient = WebClient.create("http://localhost:5000");

    public Map<String, List<Map<String, String>>> fetchData() {
        try {
            return webClient.get().uri("/fetch_diseases").retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, List<Map<String, String>>>>() {}).block();
        } catch (Exception e) { return null; }
    }
}
