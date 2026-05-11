package com.whatshapp.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatshapp.demo.model.UserSession;
import com.whatshapp.demo.model.State;
import com.whatshapp.demo.client.TranslationClient;
import com.whatshapp.demo.client.HealthClient;
import com.whatshapp.demo.client.PolicyClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageRouter {

    private final SessionService sessionService;
    private final TranslationClient translationClient;
    private final WhatsAppSender sender;
    private final HealthClient healthClient;
    private final PolicyClient policyClient;

    @Async
    public void routeAsync(JsonNode payload) {
        try {
            JsonNode message = payload.path("entry").get(0).path("changes").get(0).path("value").path("messages").get(0);
            if (message == null || message.isMissingNode()) return;

            String from = message.path("from").asText();
            String type = message.path("type").asText();

            if ("location".equals(type)) {
                UserSession session = sessionService.getOrCreate(from);
                String lang = session.getLanguage() != null ? session.getLanguage() : "en";
                double lat = message.path("location").path("latitude").asDouble();
                double lon = message.path("location").path("longitude").asDouble();
                sendTranslatedResponse(from, healthClient.findNearbyByCoords(lat, lon, "en"), lang);
                session.setState(State.MENU);
                sessionService.save(session);
                return;
            }

            String originalText = message.path("text").path("body").asText().trim();
            UserSession session = sessionService.getOrCreate(from);
            String lang = session.getLanguage() != null ? session.getLanguage() : "en";

            String text = originalText;
            if ("text".equals(type) && !originalText.isEmpty()) {
                text = "en".equals(lang) ? originalText : translationClient.translate(new TranslationClient.TranslationRequest(originalText, "en"));
            }

            if (text != null && (text.equalsIgnoreCase("menu") || text.equalsIgnoreCase("exit") || text.equalsIgnoreCase("cancel") || text.equals("0"))) {
                session.setState(State.MENU);
                session.getChatHistory().clear();
                sessionService.save(session);
                sendTranslatedResponse(from, getMenuMessage(session.getCity()), lang);
                return;
            }

            if ("text".equals(type) && isGoogleMapsLink(originalText)) {
                double[] coords = extractCoordsFromMapsLink(originalText);
                if (coords != null) {
                    sendTranslatedResponse(from, healthClient.findNearbyByCoords(coords[0], coords[1], "en"), lang);
                    session.setState(State.MENU);
                    sessionService.save(session);
                } else {
                    sendTranslatedResponse(from, "Could not read location from link. Please type your city name:", lang);
                }
                return;
            }

            if (session.getState() == State.SYMPTOM_CHAT) {
                sendTranslatedResponse(from, healthClient.continueSymptomChat(from, text, session), lang);
                return;
            }

            if (session.getState() == State.AWAITING_LOCATION && "text".equals(type)) {
                session.setState(State.MENU);
                sessionService.save(session);
                sendTranslatedResponse(from, healthClient.findNearby(text, "en"), lang);
                return;
            }

            if (session.getCity() == null) {
                String lower = text.toLowerCase().trim();
                if (lower.equals("hello") || lower.equals("hi") || lower.equals("hey") || lower.equals("start")) {
                    sendTranslatedResponse(from, "Welcome to HealthBot!\n\nPlease tell me your city (e.g. Mumbai):", lang);
                    return;
                }
                if (isLikelyCity(text)) {
                    session.setCity(text);
                    sessionService.save(session);
                    sendTranslatedResponse(from, getMenuMessage(text), lang);
                } else {
                    sendTranslatedResponse(from, "Hello! Please tell me your city:", lang);
                }
                return;
            }

            switch (text) {
                case "1" -> sendTranslatedResponse(from, healthClient.getAlerts(session.getCity(), "en"), lang);
                case "2" -> sendTranslatedResponse(from, policyClient.getPolicies(session.getCity(), "en"), lang);
                case "3" -> {
                    session.setState(State.SYMPTOM_CHAT);
                    sessionService.save(session);
                    sendTranslatedResponse(from, healthClient.startSymptomChat(from, "en"), lang);
                }
                case "4" -> {
                    session.setState(State.AWAITING_LOCATION);
                    sessionService.save(session);
                    sendTranslatedResponse(from, "Share your location or type your city name:", lang);
                }
                case "5" -> {
                    session.setCity(null);
                    sessionService.save(session);
                    sendTranslatedResponse(from, "Please tell me your new city:", lang);
                }
                default -> sendTranslatedResponse(from, getMenuMessage(session.getCity()), lang);
            }
        } catch (Exception e) {
            log.error("Error routing message: ", e);
        }
    }

    private void sendTranslatedResponse(String from, String response, String lang) {
        if (!"en".equals(lang)) response = translationClient.translate(new TranslationClient.TranslationRequest(response, lang));
        sender.send(from, response);
    }

    private boolean isGoogleMapsLink(String text) { return text.contains("google.com/maps") || text.contains("maps.app.goo.gl"); }

    private double[] extractCoordsFromMapsLink(String text) {
        try {
            Pattern atPattern = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
            Matcher atMatcher = atPattern.matcher(text);
            if (atMatcher.find()) return new double[]{Double.parseDouble(atMatcher.group(1)), Double.parseDouble(atMatcher.group(2))};
        } catch (Exception e) {}
        return null;
    }

    private String getMenuMessage(String city) {
        return String.format("*Health info for %s*\n1. Disease alerts\n2. Health schemes\n3. Check symptoms\n4. Nearest hospital\n5. Change city", city != null ? city : "your area");
    }

    private boolean isLikelyCity(String text) { return text.length() > 2 && text.length() < 40 && !text.matches("^[1-5]$"); }
}
