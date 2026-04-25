package com.whatshapp.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatshapp.demo.model.UserSession;
import com.whatshapp.demo.model.State;
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
    private final LanguageService languageService;
    private final TranslationService translationService;
    private final WhatsAppSender sender;
    private final SymptomService symptomService;
    private final DiseaseAlertService diseaseAlertService;
    private final PolicyService policyService;
    private final HospitalService hospitalService;

    @Async
    public void routeAsync(JsonNode payload) {
        try {
            JsonNode message = payload
                    .path("entry").get(0)
                    .path("changes").get(0)
                    .path("value")
                    .path("messages").get(0);

            if (message == null || message.isMissingNode()) return;

            String from = message.path("from").asText();
            String type = message.path("type").asText();

            // 1. HANDLE NATIVE LOCATION FIRST (Safety)
            if ("location".equals(type)) {
                UserSession session = sessionService.getOrCreate(from);
                String lang = session.getLanguage() != null ? session.getLanguage() : "en";
                double lat = message.path("location").path("latitude").asDouble();
                double lon = message.path("location").path("longitude").asDouble();
                sendTranslatedResponse(from, hospitalService.findNearbyByCoords(lat, lon, "en"), lang);
                session.setState(State.MENU);
                sessionService.save(session);
                return;
            }

            // 2. TEXT-BASED LOGIC
            String originalText = message.path("text").path("body").asText().trim();
            String lang = languageService.getOrSetLanguage(from, originalText);
            
            UserSession session = sessionService.getOrCreate(from);
            session.setLanguage(lang); 

            // Only translate and check commands if it's actually a text message
            String text = originalText;
            if ("text".equals(type) && !originalText.isEmpty()) {
                text = "en".equals(lang) ? originalText : translationService.translate(originalText, "en");
            }

            // Exit command guard
            if (text != null && (text.equalsIgnoreCase("menu") || text.equalsIgnoreCase("exit")
                    || text.equalsIgnoreCase("cancel") || text.equals("0"))) {
                session.setState(State.MENU);
                session.getChatHistory().clear();
                sessionService.save(session);
                sendTranslatedResponse(from, getMenuMessage(session.getCity()), lang);
                return;
            }

            // Detect Google Maps links shared as text
            if ("text".equals(type) && isGoogleMapsLink(originalText)) {
                double[] coords = extractCoordsFromMapsLink(originalText);
                if (coords != null) {
                    sendTranslatedResponse(from, hospitalService.findNearbyByCoords(coords[0], coords[1], "en"), lang);
                    session.setState(State.MENU);
                    sessionService.save(session);
                } else {
                    sendTranslatedResponse(from, "Could not read location from link. Please type your city name (e.g. Jalandhar):", lang);
                }
                return;
            }

            // Mid symptom-check conversation
            if (session.getState() == State.SYMPTOM_CHAT) {
                sendTranslatedResponse(from, symptomService.continueChat(from, text, session), lang);
                return;
            }

            // Awaiting location — fallback to city name
            if (session.getState() == State.AWAITING_LOCATION && "text".equals(type)) {
                session.setState(State.MENU);
                sessionService.save(session);
                sendTranslatedResponse(from, hospitalService.findNearby(text, "en"), lang);
                return;
            }

            // No city yet — ask for it
            if (session.getCity() == null) {
                String lower = text.toLowerCase().trim();

                // Greeting detection
                if (lower.equals("hello") || lower.equals("hi") || lower.equals("hey")
                        || lower.equals("namaste") || lower.equals("hii") || lower.equals("start")) {
                    sendTranslatedResponse(from, "Welcome to HealthBot!\n\nPlease tell me your city or district (e.g. Mumbai, Jaipur, Delhi):", lang);
                    return;
                }

                if (isLikelyCity(text)) {
                    session.setCity(text);
                    sessionService.save(session);
                    sendTranslatedResponse(from, getMenuMessage(text), lang);
                } else {
                    sendTranslatedResponse(from, "Hello! Please tell me your city or district (e.g. Mumbai, Jaipur):", lang);
                }
                return;
            }

            // Route by menu choice
            switch (text) {
                case "1" -> sendTranslatedResponse(from, diseaseAlertService.getAlerts(session.getCity(), "en"), lang);
                case "2" -> sendTranslatedResponse(from, policyService.getPolicies(session.getCity(), "en"), lang);
                case "3" -> {
                    session.setState(State.SYMPTOM_CHAT);
                    sessionService.save(session);
                    // Pass "en" to symptomService since we handle translation
                    sendTranslatedResponse(from, symptomService.startChat(from, "en"), lang);
                }
                case "4" -> {
                    session.setState(State.AWAITING_LOCATION);
                    sessionService.save(session);
                    sendTranslatedResponse(from, "Share your location:\n\n"
                            + "1. Tap attachment → Location → Current Location\n"
                            + "2. Paste a Google Maps link\n"
                            + "3. Or just type your city name (e.g. Jalandhar)", lang);
                }
                case "5" -> {
                    session.setCity(null);
                    sessionService.save(session);
                    sendTranslatedResponse(from, "Sure. Please tell me your new city or district (e.g. Mumbai, Jaipur):", lang);
                }
                default -> sendTranslatedResponse(from, getMenuMessage(session.getCity()), lang);
            }

        } catch (Exception e) {
            log.error("Error routing message from Meta: ", e);
        }
    }
    
    private void sendTranslatedResponse(String from, String response, String lang) {
        if (!"en".equals(lang)) {
            response = translationService.translate(response, lang);
        }
        sender.send(from, response);
    }

    private boolean isGoogleMapsLink(String text) {
        return text.contains("google.com/maps")
                || text.contains("maps.app.goo.gl")
                || text.contains("goo.gl/maps")
                || text.contains("maps.google.com");
    }

    private double[] extractCoordsFromMapsLink(String text) {
        try {
            // Format 1: google.com/maps/@31.2714526,75.7404516
            Pattern atPattern = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
            Matcher atMatcher = atPattern.matcher(text);
            if (atMatcher.find()) {
                double lat = Double.parseDouble(atMatcher.group(1));
                double lon = Double.parseDouble(atMatcher.group(2));
                return new double[]{lat, lon};
            }

            // Format 2: google.com/maps?q=31.2477591,75.7034001
            Pattern qPattern = Pattern.compile("[?&]q=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
            Matcher qMatcher = qPattern.matcher(text);
            if (qMatcher.find()) {
                double lat = Double.parseDouble(qMatcher.group(1));
                double lon = Double.parseDouble(qMatcher.group(2));
                return new double[]{lat, lon};
            }

            // Format 3: maps.app.goo.gl short link — resolve redirect
            if (text.contains("maps.app.goo.gl") || text.contains("goo.gl")) {
                return resolveShortenedLink(text);
            }

        } catch (Exception e) {
            log.warn("Could not extract coords from maps link: {}", text);
        }
        return null;
    }

    private double[] resolveShortenedLink(String url) {
        try {
            // Extract URL from text
            Pattern urlPattern = Pattern.compile("https?://[^\\s]+");
            Matcher urlMatcher = urlPattern.matcher(url);
            String cleanUrl = urlMatcher.find() ? urlMatcher.group() : url;

            // Follow redirect
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                    new java.net.URI(cleanUrl).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.connect();

            String redirectUrl = connection.getHeaderField("Location");
            connection.disconnect();

            if (redirectUrl != null) {
                return extractCoordsFromMapsLink(redirectUrl);
            }
        } catch (Exception e) {
            log.warn("Could not resolve shortened maps link: {}", url);
        }
        return null;
    }

    private String getMenuMessage(String city) {
        if (city == null) city = "your area";
        return String.format("""
            *Health info for %s* 🏥

            What would you like to know?
            1️⃣ Disease alerts in my area
            2️⃣ Government health schemes
            3️⃣ Check my symptoms
            4️⃣ Nearest government hospital
            5️⃣ Change my city

            Reply with a number.
            _(Type *menu* anytime to return here)_""", city);
    }

    private boolean isLikelyCity(String text) {
        return text.length() > 2 && text.length() < 40 && !text.matches("^[1-5]$");
    }
}