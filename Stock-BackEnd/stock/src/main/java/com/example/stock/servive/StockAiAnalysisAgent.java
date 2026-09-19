package com.example.stock.servive;

import com.example.stock.dto.HistoricalDataDto;
import com.example.stock.dto.MLPredictionDto;
import com.example.stock.dto.StockAiAgentDto;
import com.example.stock.dto.TechnicalAnalysisDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockAiAnalysisAgent {

    private final OpenRouterService openRouterService;
    private final ObjectMapper objectMapper;

    @Value("${openrouter.model:google/gemma-4-31b-it:free}")
    private String modelName;

    // Cache responses for 30 minutes to conserve OpenRouter quota
    private final Map<String, CacheEntry> agentCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    private static class CacheEntry {
        final StockAiAgentDto data;
        final long timestamp;

        CacheEntry(StockAiAgentDto data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > CACHE_TTL_MS;
        }
    }

    public StockAiAgentDto analyzeStock(String ticker,
                                       HistoricalDataDto historicalData,
                                       TechnicalAnalysisDto technicals,
                                       MLPredictionDto mlPrediction) {
        // 1. Check cache first
        String cacheKey = ticker.toUpperCase();
        CacheEntry cached = agentCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.info("Returning cached AI Agent analysis for {}", ticker);
            return cached.data;
        }

        try {
            // 2. Build prompt
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(ticker, historicalData, technicals, mlPrediction);

            log.info("AI Stock Agent requesting analysis for {} from OpenRouter ({})", ticker, modelName);
            String aiRawResponse = openRouterService.callAiWithPrompt(systemPrompt, userPrompt);

            // 3. Parse JSON response
            StockAiAgentDto result = parseAiResponse(aiRawResponse);
            result.setAgentModel(modelName);
            result.setTimestamp(System.currentTimeMillis());

            // 4. Store in cache
            agentCache.put(cacheKey, new CacheEntry(result));
            return result;

        } catch (Exception e) {
            log.warn("AI Stock Agent call failed for {}: {}. Generating rule-based fallback.", ticker, e.getMessage());
            return generateFallback(ticker, historicalData, technicals, mlPrediction, e.getMessage());
        }
    }

    private String buildSystemPrompt() {
        return """
            You are a Senior Quantitative & Fundamental Equity Analyst AI Agent.
            Your job is to analyze real-time market data, technical indicators, and machine learning models for a given stock and provide an actionable, institutional-grade equity analysis.

            You MUST respond in pure, valid JSON with NO markdown formatting, NO backticks, and NO conversational prose:
            {
              "sentiment": "BULLISH",
              "executiveSummary": "Concise 2-sentence summary of the stock's current posture and primary catalyst.",
              "bullCase": [
                "Key reason 1 for potential upside",
                "Key reason 2 for potential upside"
              ],
              "bearCase": [
                "Key risk 1 or downside pressure",
                "Key risk 2 or downside pressure"
              ],
              "keyRisks": [
                "Critical macro or technical risk to watch",
                "Secondary risk factor"
              ],
              "valuationAssessment": "1 sentence evaluating current price relative to moving averages, recent support/resistance, and volume."
            }
            """;
    }

    private String buildUserPrompt(String ticker,
                                   HistoricalDataDto data,
                                   TechnicalAnalysisDto ta,
                                   MLPredictionDto ml) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze stock: ").append(ticker).append("\n\n");

        if (data != null) {
            sb.append("=== MARKET DATA ===\n")
              .append(String.format("Current Close: $%.2f | Open: $%.2f | High: $%.2f | Low: $%.2f\n",
                      data.getClose(), data.getOpen(), data.getHigh(), data.getLow()))
              .append(String.format("Volume: %,d | Prev Close: $%.2f | 30-Day MA: $%.2f\n\n",
                      data.getVolume(), data.getPreviousClose(), data.getMovingAverage30()));
        }

        if (ta != null) {
            sb.append("=== TECHNICAL INDICATORS ===\n")
              .append(String.format("Trend: %s | Support: $%.2f | Resistance: $%.2f | 7-Day Change: %s\n",
                      ta.getTrend(), ta.getSupport(), ta.getResistance(), ta.getChange7d()))
              .append("Signals: ").append(ta.getSignals() != null ? String.join(", ", ta.getSignals()) : "None")
              .append("\n\n");
        }

        if (ml != null) {
            sb.append("=== MACHINE LEARNING PREDICTION ===\n")
              .append(String.format("Forecast Price: $%.2f (Confidence: %.1f%%, Trend: %s)\n",
                      ml.getPredictedPrice(), ml.getConfidenceScore(), ml.getTrend()))
              .append(String.format("Pattern: %s (Confidence: %.1f%%, Action: %s)\n",
                      ml.getDetectedPattern(), ml.getPatternConfidence(), ml.getPatternRecommendation()));
        }

        return sb.toString();
    }

    private StockAiAgentDto parseAiResponse(String raw) {
        try {
            String clean = raw.trim();
            if (clean.startsWith("```json")) {
                clean = clean.substring(7);
            } else if (clean.startsWith("```")) {
                clean = clean.substring(3);
            }
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.length() - 3);
            }
            clean = clean.trim();

            JsonNode root = objectMapper.readTree(clean);

            String sentiment = root.path("sentiment").asText("NEUTRAL").toUpperCase();
            String summary = root.path("executiveSummary").asText("Analysis completed based on current technical and ML indicators.");
            String valuation = root.path("valuationAssessment").asText("Price trading within technical bounds.");

            List<String> bullCase = extractList(root.path("bullCase"));
            List<String> bearCase = extractList(root.path("bearCase"));
            List<String> keyRisks = extractList(root.path("keyRisks"));

            return StockAiAgentDto.builder()
                    .sentiment(sentiment)
                    .executiveSummary(summary)
                    .bullCase(bullCase)
                    .bearCase(bearCase)
                    .keyRisks(keyRisks)
                    .valuationAssessment(valuation)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse AI Agent response as JSON: {}", raw, e);
            throw new RuntimeException("Parse failed: " + e.getMessage(), e);
        }
    }

    private List<String> extractList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                list.add(item.asText());
            }
        }
        return list;
    }

    private StockAiAgentDto generateFallback(String ticker,
                                             HistoricalDataDto data,
                                             TechnicalAnalysisDto ta,
                                             MLPredictionDto ml,
                                             String reason) {
        String sentiment = (ta != null && "BULLISH".equalsIgnoreCase(ta.getTrend())) ? "BULLISH" : "NEUTRAL";
        double close = data != null ? data.getClose() : 0.0;
        double support = ta != null ? ta.getSupport() : 0.0;
        double resistance = ta != null ? ta.getResistance() : 0.0;

        List<String> bull = new ArrayList<>();
        bull.add("Trading above key support level at $" + String.format("%.2f", support));
        if (ml != null && ml.getPredictedPrice() > close) {
            bull.add("ML model projects upward target near $" + String.format("%.2f", ml.getPredictedPrice()));
        }

        List<String> bear = new ArrayList<>();
        bear.add("Nearby resistance at $" + String.format("%.2f", resistance));
        if (ml != null && ml.getPredictedPrice() < close) {
            bear.add("ML model signals short-term pullback toward $" + String.format("%.2f", ml.getPredictedPrice()));
        }

        List<String> risks = new ArrayList<>();
        risks.add("Broader equity market volatility and interest rate pressure.");
        risks.add("Break below support level at $" + String.format("%.2f", support) + " invalidates setup.");

        return StockAiAgentDto.builder()
                .sentiment(sentiment)
                .executiveSummary(String.format("%s is currently trading at $%.2f with %s momentum. Primary resistance is at $%.2f.",
                        ticker, close, sentiment.toLowerCase(), resistance))
                .bullCase(bull)
                .bearCase(bear)
                .keyRisks(risks)
                .valuationAssessment(String.format("Price is positioned between support ($%.2f) and resistance ($%.2f).", support, resistance))
                .agentModel(modelName + " (Fallback: " + reason + ")")
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
