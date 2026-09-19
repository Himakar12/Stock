package com.example.stock.servive;

import com.example.stock.dto.MLPredictionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MLService {

    // LSTM retraining on every request takes several seconds — give it real
    // headroom instead of the RestTemplate default of "wait forever."
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private static final String ML_BASE_URL = "http://localhost:8000";

    public MLService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Send raw historical arrays to Python. Python NO LONGER calls yfinance.
     *
     * Matches the actual ml_service.py response contract:
     *   POST /forecast       -> {ticker, last_close, predictions[], days_to_predict,
     *                             train_mae, confidence, data_source}
     *   POST /detect-pattern  -> {ticker, pattern, confidence, trend, details}
     *
     * Throws MLServiceException on any failure — callers must NOT receive a
     * fabricated zero-value prediction, since that number flows straight into
     * DebateOrchestratorService's disagreement scoring and then into the
     * Gemini/Groq prompts as if it were real.
     */
    public MLPredictionDto getMlPrediction(String ticker, List<Double> closes, List<Double> lows,
                                            int daysToPredict) {
        try {
            // 1. LSTM Forecast
            Map<String, Object> forecastBody = Map.of(
                    "ticker", ticker,
                    "days_to_predict", daysToPredict,
                    "historical_closes", closes
            );
            ResponseEntity<String> forecastResponse = restTemplate.postForEntity(
                    ML_BASE_URL + "/forecast", forecastBody, String.class
            );
            JsonNode forecast = objectMapper.readTree(forecastResponse.getBody());

            if (!forecast.has("predictions") || forecast.path("predictions").isEmpty()) {
                throw new MLServiceException(
                        "Forecast response missing predictions for " + ticker + ": " + forecastResponse.getBody());
            }
            if (!forecast.has("last_close")) {
                throw new MLServiceException(
                        "Forecast response missing last_close for " + ticker + ": " + forecastResponse.getBody());
            }

            double lastClose = forecast.path("last_close").asDouble();
            double predictedPrice = forecast.path("predictions").get(0).asDouble();

            // ml_service.py doesn't return a trend field — derive it from the
            // forecast direction relative to the last known close.
            String trend = predictedPrice > lastClose ? "bullish"
                    : predictedPrice < lastClose ? "bearish"
                    : "neutral";

            // ml_service.py's "confidence" is 0.0-1.0. Normalize to a 0-100
            // scale here so every downstream %.1f%% format string (debate
            // prompts, Gemini synthesis prompt) shows "99.8%" instead of a
            // silently-wrong "1.0%".
            double confidence = to0to100(forecast.path("confidence").asDouble());

            // train_mae is Mean Absolute Error in price units, not RMSE —
            // named modelRmse here only to match the existing DTO field;
            // treat it as "typical forecast error in $" either way.
            double trainMae = forecast.path("train_mae").asDouble();

            // 2. Pattern Detection
            Map<String, Object> patternBody = Map.of(
                    "ticker", ticker,
                    "closes", closes,
                    "lows", lows
            );
            ResponseEntity<String> patternResponse = restTemplate.postForEntity(
                    ML_BASE_URL + "/detect-pattern", patternBody, String.class
            );
            JsonNode pattern = objectMapper.readTree(patternResponse.getBody());

            String detectedPattern = pattern.path("pattern").asText("no_clear_pattern");
            double patternConfidence = to0to100(pattern.path("confidence").asDouble());
            // ml_service.py returns no recommendation string — synthesize one
            // from the pattern label rather than leaving it blank.
            String recommendation = recommendationFor(detectedPattern);

            return MLPredictionDto.builder()
                    .ticker(ticker)
                    .predictedPrice(predictedPrice)
                    .confidenceScore(confidence)
                    .trend(trend)
                    .modelRmse(trainMae)
                    .detectedPattern(detectedPattern)
                    .patternConfidence(patternConfidence)
                    .patternRecommendation(recommendation)
                    .build();

        } catch (RestClientException e) {
            log.error("ML service unreachable/failed for ticker: {}", ticker, e);
            throw new MLServiceException("ML service call failed for " + ticker + ": " + e.getMessage(), e);
        } catch (MLServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("ML response parsing failed for ticker: {}", ticker, e);
            throw new MLServiceException("Could not parse ML response for " + ticker + ": " + e.getMessage(), e);
        }
    }

    /** Convenience overload preserving the previous 1-day-ahead behavior. */
    public MLPredictionDto getMlPrediction(String ticker, List<Double> closes, List<Double> lows) {
        return getMlPrediction(ticker, closes, lows, 1);
    }

    /**
     * ml_service.py's confidence fields are already on 0.0-1.0. If some
     * future version of the Python service starts returning 0-100 directly,
     * this guards against double-scaling instead of silently producing a
     * >100% confidence value.
     */
    private double to0to100(double rawConfidence) {
        return rawConfidence <= 1.0 ? rawConfidence * 100.0 : rawConfidence;
    }

    private String recommendationFor(String pattern) {
        return switch (pattern) {
            case "overbought_possible_reversal" -> "Momentum looks stretched — consider trimming or tightening stops.";
            case "oversold_possible_reversal" -> "Momentum looks stretched to the downside — a bounce may be near.";
            case "double_top" -> "Possible topping pattern — watch for a breakdown below recent support.";
            case "double_bottom" -> "Possible bottoming pattern — watch for a breakout above recent resistance.";
            default -> "No strong chart pattern detected — lean on trend and technical levels instead.";
        };
    }

    /**
     * Thrown when the ML service can't produce a usable prediction. Callers
     * (StockAnalysisService) should let this propagate — a failed analysis is
     * better than one silently built on a fabricated $0.00 forecast.
     */
    public static class MLServiceException extends RuntimeException {
        public MLServiceException(String message) {
            super(message);
        }

        public MLServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}