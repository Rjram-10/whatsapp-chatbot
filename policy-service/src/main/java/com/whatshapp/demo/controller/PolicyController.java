package com.whatshapp.demo.controller;

import com.whatshapp.demo.service.PolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/policy")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService policyService;

    @GetMapping("/schemes")
    public String getPolicies(@RequestParam String city, @RequestParam String lang) {
        return policyService.getPolicies(city, lang);
    }
}
