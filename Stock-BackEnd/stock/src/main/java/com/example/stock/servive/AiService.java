package com.example.stock.servive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;

    public AiService(
            ObjectMapper objectMapper,
            @Value("${ai.api.url}") String apiUrl,
            @Value("${ai.api.key}") String apiKey) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    public String callAiWithPrompt(String systemPrompt, String userPrompt) {
        try {
            // Gemini uses the API key as a query parameter
            String url = UriComponentsBuilder.fromUriString(apiUrl)
                    .queryParam("key", apiKey)
                    .toUriString();

            // Gemini request format (different from OpenAI)
            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of(
                        "role", "user",
                        "parts", List.of(
                            Map.of("text", systemPrompt + "\n\n" + userPrompt)
                        )
                    )
                ),
                "generationConfig", Map.of(
                    "temperature", 0.3,
                    "maxOutputTokens", 1500,
                    "responseMimeType", "application/json"
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("Calling Gemini API...");

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            // Parse Gemini response (different JSON structure)
            JsonNode root = objectMapper.readTree(response.getBody());
            
            // Extract text from candidates -> content -> parts -> text
            String jsonText = root.path("candidates")
                    .get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            // Gemini sometimes wraps JSON in markdown code blocks
            String cleanJson = jsonText
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            log.info("Gemini response received. Length: {} chars", cleanJson.length());
            return cleanJson;

        } catch (Exception e) {
            log.error("Gemini API call failed", e);
            throw new RuntimeException("AI analysis failed: " + e.getMessage(), e);
        }
    }
}