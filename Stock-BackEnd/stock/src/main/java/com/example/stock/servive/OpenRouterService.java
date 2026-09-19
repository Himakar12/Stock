package com.example.stock.servive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
public class OpenRouterService {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openrouter.api-key}")
    private String apiKey;

    @Value("${openrouter.model}")
    private String model;

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";

    public String callAiWithPrompt(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", new Object[]{
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    }
            );

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENROUTER_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    // OpenRouter asks for these two for attribution/rankings;
                    // not required but good practice, replace with your real values
                    .header("HTTP-Referer", "http://localhost:4200")
                    .header("X-Title", "Stock Analysis App")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.info("Calling OpenRouter API with model: {}", model);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new OpenRouterException(
                        "OpenRouter returned status " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");

            if (!choices.isArray() || choices.isEmpty()) {
                throw new OpenRouterException("OpenRouter response missing choices: " + response.body());
            }

            String content = choices.get(0).path("message").path("content").asText();
            log.info("OpenRouter response received. Length: {} chars", content.length());
            return content;

        } catch (OpenRouterException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenRouterException("OpenRouter API call failed: " + e.getMessage(), e);
        }
    }

    public static class OpenRouterException extends RuntimeException {
        public OpenRouterException(String message) {
            super(message);
        }
        public OpenRouterException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}