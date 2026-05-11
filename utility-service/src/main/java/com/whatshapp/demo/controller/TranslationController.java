package com.whatshapp.demo.controller;

import com.whatshapp.demo.service.TranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/translation")
@RequiredArgsConstructor
public class TranslationController {

    private final TranslationService translationService;

    @PostMapping("/translate")
    public String translate(@RequestBody TranslationRequest request) {
        return translationService.translate(request.getText(), request.getTargetLang());
    }

    public static class TranslationRequest {
        private String text;
        private String targetLang;
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getTargetLang() { return targetLang; }
        public void setTargetLang(String targetLang) { this.targetLang = targetLang; }
    }
}
