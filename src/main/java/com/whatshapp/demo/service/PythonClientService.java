package com.whatshapp.demo.service;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;

@Service
public class PythonClientService {

    private final WebClient webClient = WebClient.create("http://localhost:5000");

    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, String>>> fetchData() {
        return webClient.get()
                .uri("/idsp")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }
}
