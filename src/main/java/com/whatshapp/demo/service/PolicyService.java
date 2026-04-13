package com.whatshapp.demo.service;

import org.springframework.stereotype.Service;

@Service
public class PolicyService {
    public String getPolicies(String city, String lang) {
        if ("hi".equals(lang)) {
            return "Ayushman Bharat yojna ke tahet aap 5 lakh tak ka muft ilaaj pa sakte hain.";
        }
        return "Under Ayushman Bharat, you can avail free treatment up to 5 lakhs.";
    }
}
