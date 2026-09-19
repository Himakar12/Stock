package com.example.stock.servive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class VisionService {

    private static final String UPLOAD_DIR = "./uploads/";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;

    public VisionService(
            ObjectMapper objectMapper,
            @Value("${ai.api.url}") String apiUrl,
            @Value("${ai.api.key}") String apiKey) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    /**
     * Identify the stock ticker from a chart image using Gemini's vision capability.
     * Sends the actual image bytes to Gemini and asks it to read the ticker off the chart.
     */
    public String identifyTicker(MultipartFile image) {
        String filename = image.getOriginalFilename();
        log.info("Identifying ticker from image: {}", filename);

        try {
            String base64Image = Base64.getEncoder().encodeToString(image.getBytes());
            String mimeType = image.getContentType() != null ? image.getContentType() : "image/png";

            String url = apiUrl + "?key=" + apiKey;

            String prompt = "Look at this stock/trading chart image. Identify the ticker symbol "
                    + "shown on the chart (e.g. AAPL, TSLA, GOOGL, MGC1!). "
                    + "Respond with ONLY valid JSON in this exact format, no markdown, no extra text: "
                    + "{\"ticker\": \"SYMBOL\", \"confidence\": 0.0}. "
                    + "confidence should be a number between 0 and 1 reflecting how clearly the ticker "
                    + "is visible in the image. If you cannot identify a ticker, use "
                    + "{\"ticker\": \"UNKNOWN\", \"confidence\": 0.0}.";

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of(
                        "role", "user",
                        "parts", List.of(
                            Map.of("text", prompt),
                            Map.of("inline_data", Map.of(
                                "mime_type", mimeType,
                                "data", base64Image
                            ))
                        )
                    )
                ),
                "generationConfig", Map.of(
                    "temperature", 0.1,
                    "maxOutputTokens", 100,
                    "responseMimeType", "application/json"
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            String jsonText = root.path("candidates")
                    .get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            String cleanJson = jsonText.replace("```json", "").replace("```", "").trim();
            JsonNode result = objectMapper.readTree(cleanJson);

            String ticker = result.path("ticker").asText("UNKNOWN");
            double confidence = result.path("confidence").asDouble(0.0);

            log.info("Gemini identified ticker: {} (confidence: {})", ticker, confidence);

            if ("UNKNOWN".equalsIgnoreCase(ticker) || ticker.isBlank()) {
                throw new RuntimeException(
                    "Could not identify a stock ticker from the uploaded image. "
                    + "Please upload a clearer chart image showing the ticker symbol.");
            }

            return ticker.toUpperCase();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Vision identification failed for image: {}", filename, e);
            throw new RuntimeException("Failed to analyze chart image: " + e.getMessage(), e);
        }
    }

    public String saveImage(MultipartFile image) {
        try {
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String fileName = UUID.randomUUID() + "_" + image.getOriginalFilename();
            Path filePath = uploadPath.resolve(fileName);
            Files.copy(image.getInputStream(), filePath);

            return filePath.toString();
        } catch (Exception e) {
            log.error("Failed to save image", e);
            throw new RuntimeException("Image save failed", e);
        }
    }
}