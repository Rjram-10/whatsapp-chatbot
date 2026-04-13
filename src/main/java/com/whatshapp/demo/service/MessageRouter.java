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
            String text = message.path("text").path("body").asText().trim();
            UserSession session = sessionService.getOrCreate(from);
            String lang = languageService.detect(text, session);
            session.setLanguage(lang);

            // Exit command — works from any state
            if (text.equalsIgnoreCase("menu") || text.equalsIgnoreCase("exit")
                    || text.equalsIgnoreCase("cancel") || text.equals("0")) {
                session.setState(State.MENU);
                session.getChatHistory().clear();
                sessionService.save(session);
                sender.send(from, getMenuMessage(lang, session.getCity()));
                return;
            }

            // Detect Google Maps links shared as text
            if ("text".equals(type) && isGoogleMapsLink(text)) {
                double[] coords = extractCoordsFromMapsLink(text);
                if (coords != null) {
                    sender.send(from, hospitalService.findNearbyByCoords(coords[0], coords[1], lang));
                    session.setState(State.MENU);
                    sessionService.save(session);
                } else {
                    sender.send(from, "hi".equals(lang)
                            ? "Link se location nahi mili. Apna shehar type karein (jaise: Jalandhar):"
                            : "Could not read location from link. Please type your city name (e.g. Jalandhar):");
                }
                return;
            }

            // Handle native WhatsApp GPS location message
            if ("location".equals(type)) {
                double lat = message.path("location").path("latitude").asDouble();
                double lon = message.path("location").path("longitude").asDouble();
                sender.send(from, hospitalService.findNearbyByCoords(lat, lon, lang));
                session.setState(State.MENU);
                sessionService.save(session);
                return;
            }

            // Mid symptom-check conversation
            if (session.getState() == State.SYMPTOM_CHAT) {
                sender.send(from, symptomService.continueChat(from, text, session));
                return;
            }

            // Awaiting location — fallback to city name
            if (session.getState() == State.AWAITING_LOCATION && "text".equals(type)) {
                session.setState(State.MENU);
                sessionService.save(session);
                sender.send(from, hospitalService.findNearby(text, lang));
                return;
            }

            // No city yet — ask for it
            if (session.getCity() == null) {
                String lower = text.toLowerCase().trim();

                // Greeting detection
                if (lower.equals("hello") || lower.equals("hi") || lower.equals("hey")
                        || lower.equals("namaste") || lower.equals("hii") || lower.equals("start")) {
                    sender.send(from, lang.equals("hi")
                            ? "Namaste! Swagat hai Health Bot mein.\n\nApna shehar ya zila batayein (jaise: Mumbai, Jaipur, Delhi):"
                            : "Welcome to HealthBot!\n\nPlease tell me your city or district (e.g. Mumbai, Jaipur, Delhi):");
                    return;
                }

                if (isLikelyCity(text)) {
                    session.setCity(text);
                    sessionService.save(session);
                    sender.send(from, getMenuMessage(lang, text));
                } else {
                    sender.send(from, lang.equals("hi")
                            ? "Namaste! Apna shehar ya zila batayein (jaise: Mumbai, Jaipur):"
                            : "Hello! Please tell me your city or district (e.g. Mumbai, Jaipur):");
                }
                return;
            }

            // Route by menu choice
            switch (text) {
                case "1" -> sender.send(from, diseaseAlertService.getAlerts(session.getCity(), lang));
                case "2" -> sender.send(from, policyService.getPolicies(session.getCity(), lang));
                case "3" -> {
                    session.setState(State.SYMPTOM_CHAT);
                    sessionService.save(session);
                    sender.send(from, symptomService.startChat(from, lang));
                }
                case "4" -> {
                    session.setState(State.AWAITING_LOCATION);
                    sessionService.save(session);
                    sender.send(from, "hi".equals(lang)
                            ? "Apni location share karein:\n\n"
                            + "1. WhatsApp attachment → Location → Current Location\n"
                            + "2. Google Maps link paste karein\n"
                            + "3. Ya apna shehar type karein (jaise: Jalandhar)"
                            : "Share your location:\n\n"
                            + "1. Tap attachment → Location → Current Location\n"
                            + "2. Paste a Google Maps link\n"
                            + "3. Or just type your city name (e.g. Jalandhar)");
                }
                default -> sender.send(from, getMenuMessage(lang, session.getCity()));
            }

        } catch (Exception e) {
            log.error("Error routing message from Meta: ", e);
        }
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
                    new java.net.URL(cleanUrl).openConnection();
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

    private String getMenuMessage(String lang, String city) {
        if (city == null) city = "your area";
        if ("hi".equals(lang)) {
            return String.format("""
                *%s ke liye health jaankari* 🏥

                Kya jaanna chahte hain?
                1️⃣ Mere area mein bimariyan
                2️⃣ Sarkari yojnayen
                3️⃣ Mere lakshan check karein
                4️⃣ Nazdeeki sarkari hospital

                Ek number bhejein.
                _(Kabhi bhi *menu* type karein wapas aane ke liye)_""", city);
        }
        return String.format("""
            *Health info for %s* 🏥

            What would you like to know?
            1️⃣ Disease alerts in my area
            2️⃣ Government health schemes
            3️⃣ Check my symptoms
            4️⃣ Nearest government hospital

            Reply with a number.
            _(Type *menu* anytime to return here)_""", city);
    }

    private boolean isLikelyCity(String text) {
        return text.length() > 2 && text.length() < 40 && !text.matches("^[1-4]$");
    }
}