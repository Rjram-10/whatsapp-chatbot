package com.whatshapp.demo.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "utility-service", url = "${services.utility.url:http://localhost:8083}")
public interface TranslationClient {

    @PostMapping("/api/translation/translate")
    String translate(@RequestBody TranslationRequest request);

    class TranslationRequest {
        private String text;
        private String targetLang;
        public TranslationRequest(String text, String targetLang) {
            this.text = text;
            this.targetLang = targetLang;
        }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getTargetLang() { return targetLang; }
        public void setTargetLang(String targetLang) { this.targetLang = targetLang; }
    }
}
