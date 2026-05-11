package com.whatshapp.demo.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "policy-service", url = "${services.policy.url:http://localhost:8082}")
public interface PolicyClient {

    @GetMapping("/api/policy/schemes")
    String getPolicies(@RequestParam("city") String city, @RequestParam("lang") String lang);
}
