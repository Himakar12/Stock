package com.example.stock.servive;

import com.example.stock.dto.*;
import com.example.stock.entity.StockAnalysis;
import com.example.stock.repository.StockAnalysisRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnalysisService {

    private final StockAnalysisRepository repository;
    private final VisionService visionService;
    private final MarketDataService marketDataService;
    private final MLService mlService;
    private final AiService aiService;
    private final DebateOrchestratorService debateOrchestrator; // NEW
    private final StockAiAnalysisAgent stockAiAnalysisAgent;
    private final ObjectMapper objectMapper;

    @Transactional
    public AnalysisResultDto analyzeStock(MultipartFile image, Long userId) {
        try {
            // 1. Vision OCR reads ticker from image
            String imagePath = visionService.saveImage(image);
            String ticker = visionService.identifyTicker(image);
            log.info("Vision identified ticker: {}", ticker);

            // 2. ONE Yahoo Finance call → get latest data + full history arrays
            MarketDataResult marketResult = marketDataService.fetchMarketData(ticker);
            HistoricalDataDto historicalData = marketResult.getLatestData();
            log.info("Yahoo data for {}: Close=${}", ticker, historicalData.getClose());

            // 3. Calculate technicals in Java
            TechnicalAnalysisDto technicalAnalysis = marketDataService.calculateTechnicalAnalysis(historicalData);

            // 4. Pass raw arrays to Python ML (NO second Yahoo call)
            MLPredictionDto mlPrediction = mlService.getMlPrediction(
                    ticker,
                    marketResult.getCloses(),
                    marketResult.getLows()
            );
            log.info("ML prediction: price=${}, trend={}, pattern={}",
                    mlPrediction.getPredictedPrice(),
                    mlPrediction.getTrend(),
                    mlPrediction.getDetectedPattern());

            // 4.5 NEW: Gated debate — only fires when ML and technicals actually disagree
            DebateResult debate = debateOrchestrator.runDebate(
                    ticker, historicalData, technicalAnalysis, mlPrediction);

            if (debate.isDebateTriggered()) {
                log.info("Debate triggered for {} (score: {}). Synthesis: {}",
                        ticker, debate.getDisagreementScore(), debate.getFinalSynthesis());
            } else {
                log.info("No debate needed for {} — signals agreed (score: {})",
                        ticker, debate.getDisagreementScore());
            }

            // 4.8 Run Stock AI Analysis Agent (OpenRouter)
            StockAiAgentDto aiInsights = stockAiAnalysisAgent.analyzeStock(
                    ticker, historicalData, technicalAnalysis, mlPrediction);

            // 5. MySQL history for Strategy B
            List<StockAnalysis> previousAnalyses = repository
                    .findTop3ByTickerOrderByCreatedAtDesc(ticker);
            String historyContext = buildHistoryContext(previousAnalyses);

            // 6. Build prompt with everything, including debate result if present
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(ticker, historicalData, technicalAnalysis,
                    historyContext, mlPrediction, debate); // NEW: debate passed in

            // 7. Gemini LLM
            String aiResponse = aiService.callAiWithPrompt(systemPrompt, userPrompt);

            // 8. Parse
            PredictionDto prediction = parseAiResponse(aiResponse);

            // 9. Save to MySQL
            StockAnalysis analysis = StockAnalysis.builder()
                    .userId(userId)
                    .ticker(ticker)
                    .imagePath(imagePath)
                    .confidenceScore(prediction.getConfidence())
                    .historicalDataJson(objectMapper.writeValueAsString(historicalData))
                    .technicalAnalysisJson(objectMapper.writeValueAsString(technicalAnalysis))
                    .predictionJson(objectMapper.writeValueAsString(prediction))
                    .promptSent(userPrompt)
                    .rawAiResponse(aiResponse)
                    .build();

            repository.save(analysis);
            log.info("Analysis complete. Saved to DB with id: {}", analysis.getId());

            // 10. Return
            return AnalysisResultDto.builder()
                    .ticker(ticker)
                    .confidence(prediction.getConfidence())
                    .historicalData(historicalData)
                    .analysis(technicalAnalysis)
                    .prediction(prediction)
                    .mlPrediction(mlPrediction)
                    .aiInsights(aiInsights)
                    .debateResult(debate)
                    .build();

        } catch (Exception e) {
            log.error("Stock analysis pipeline failed", e);
            throw new RuntimeException("Analysis failed: " + e.getMessage(), e);
        }
    }

    // ─── Prompt Engineering ───

    private String buildSystemPrompt() {
        return """
            You are an elite quantitative technical analyst with 20 years of experience.

            You receive:
            1. REAL-TIME MARKET DATA (from Yahoo Finance)
            2. TECHNICAL INDICATORS (support, resistance, moving averages)
            3. MACHINE LEARNING PREDICTIONS (LSTM forecast + chart patterns)
            4. YOUR PREVIOUS ANALYSES (track record from database)
            5. A DEBATE TRANSCRIPT (only present when ML and technicals disagreed)

            === CONFIDENCE SCORING RUBRIC (MANDATORY) ===
            90-100: STRONG CONFLUENCE — all signals agree, no conflict
            75-89: SOLID AGREEMENT — two of three agree, minor conflict
            60-74: MIXED SIGNALS — some disagreement, near key levels
            40-59: WEAK CONFLUENCE — signals contradict, chop zone
            0-39: UNCERTAIN — strong disagreement, would not trade this

            === SENTIMENT RULES ===
            STRONG_BUY: confidence >= 85 AND bullish across all signals
            BUY: confidence 60-84 AND bullish bias
            HOLD: confidence 40-59 OR mixed/neutral signals
            SELL: confidence 60-84 AND bearish bias
            STRONG_SELL: confidence >= 85 AND bearish across all signals

            === OUTPUT RULES ===
            1. ALWAYS vary your confidence. Never default to the same number.
            2. If your previous prediction was wrong, LOWER your confidence or explain why.
            3. If ML and technicals disagree, confidence MUST be <= 70.
            4. If a debate transcript is present, your reasoning MUST reference which side's argument you found more convincing.
            5. Respond ONLY in valid JSON:

            {
              "low": 175.50,
              "high": 182.00,
              "confidence": 78,
              "sentiment": "BUY",
              "reasoning": "Detailed explanation referencing specific signals..."
            }
            """;
    }

    private String buildUserPrompt(String ticker, HistoricalDataDto data,
                                   TechnicalAnalysisDto ta, String historyContext,
                                   MLPredictionDto ml, DebateResult debate) { // NEW param

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== STOCK ANALYSIS REQUEST ===\n\n");
        prompt.append("Ticker: ").append(ticker).append("\n\n");

        prompt.append("=== REAL-TIME MARKET DATA (Yahoo Finance) ===\n");
        prompt.append(String.format("Date: %s\n", data.getDate()));
        prompt.append(String.format("Open: $%.2f\n", data.getOpen()));
        prompt.append(String.format("High: $%.2f\n", data.getHigh()));
        prompt.append(String.format("Low: $%.2f\n", data.getLow()));
        prompt.append(String.format("Close: $%.2f\n", data.getClose()));
        prompt.append(String.format("Volume: %,d\n", data.getVolume()));
        prompt.append(String.format("30-Day MA: $%.2f\n", data.getMovingAverage30()));
        prompt.append(String.format("Market Status: %s\n", data.getMarketStatus()));
        if ("REGULAR".equals(data.getMarketStatus())) {
            prompt.append("NOTE: The market is currently OPEN. Close, high, low, support, and resistance ");
            prompt.append("reflect a live, still-moving intraday price, not a finalized daily close. ");
            prompt.append("Frame your reasoning accordingly — do not describe this as \"today's close.\"\n");
        }
        prompt.append("\n");
        prompt.append("=== TECHNICAL ANALYSIS ===\n");
        prompt.append(String.format("Trend: %s\n", ta.getTrend().toUpperCase()));
        prompt.append(String.format("Support: $%.2f\n", ta.getSupport()));
        prompt.append(String.format("Resistance: $%.2f\n", ta.getResistance()));
        prompt.append(String.format("7-Day Change: %s\n", ta.getChange7d()));
        prompt.append(String.format("Volume Signal: %s\n", ta.getVolumeSignal()));
        prompt.append("Signals: ").append(String.join(", ", ta.getSignals())).append("\n\n");
        
        prompt.append("=== MACHINE LEARNING PREDICTIONS ===\n");
        prompt.append(String.format("LSTM Forecast (next close): $%.2f (model confidence: %.1f%%)\n",
                ml.getPredictedPrice(), ml.getConfidenceScore()));
        prompt.append(String.format("ML Trend Signal: %s (model RMSE: %.4f)\n",
                ml.getTrend(), ml.getModelRmse()));
        prompt.append(String.format("Chart Pattern Detected: %s (confidence: %.1f%%)\n",
                ml.getDetectedPattern(), ml.getPatternConfidence()));
        prompt.append(String.format("Pattern Recommendation: %s\n\n",
                ml.getPatternRecommendation()));

        // NEW: Debate transcript, only when triggered
        if (debate != null && debate.isDebateTriggered()) {
            prompt.append("=== DEBATE TRANSCRIPT (signals disagreed) ===\n");
            prompt.append("ML Advocate argued: ").append(debate.getMlAdvocatePosition()).append("\n\n");
            prompt.append("Technical Advocate argued: ").append(debate.getTechnicalAdvocatePosition()).append("\n\n");
            prompt.append("Preliminary synthesis: ").append(debate.getFinalSynthesis()).append("\n\n");
        }

        if (!historyContext.isEmpty()) {
            prompt.append("=== YOUR PREVIOUS ANALYSES FOR ").append(ticker).append(" ===\n");
            prompt.append(historyContext).append("\n\n");
        }

        prompt.append("=== INSTRUCTION ===\n");
        prompt.append("Synthesize the real market data, technical indicators, ML predictions, ");
        prompt.append("the debate transcript (if present), and your previous track record into a single coherent prediction. ");
        prompt.append("If signals conflict, explain your reasoning clearly. ");
        prompt.append("Respond ONLY in the exact JSON format specified.");

        return prompt.toString();
    }

    private String buildHistoryContext(List<StockAnalysis> previousAnalyses) {
        if (previousAnalyses == null || previousAnalyses.isEmpty()) {
            return "";
        }

        return previousAnalyses.stream().map(analysis -> {
            try {
                PredictionDto past = objectMapper.readValue(
                        analysis.getPredictionJson(), PredictionDto.class);

                return String.format(
                        "Date: %s | Predicted Range: $%.2f-$%.2f | Sentiment: %s | Confidence: %d%%",
                        analysis.getCreatedAt().toLocalDate(),
                        past.getLow(), past.getHigh(),
                        past.getSentiment(),
                        past.getConfidence()
                );
            } catch (JsonProcessingException e) {
                return "Previous analysis on " + analysis.getCreatedAt().toLocalDate();
            }
        }).collect(Collectors.joining("\n"));
    }

    private PredictionDto parseAiResponse(String aiResponse) {
        try {
            String cleanJson = aiResponse
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            return objectMapper.readValue(cleanJson, PredictionDto.class);

        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", aiResponse, e);
            return PredictionDto.builder()
                    .low(0.0)
                    .high(0.0)
                    .confidence(0)
                    .sentiment("HOLD")
                    .reasoning("Parse error: " + e.getMessage())
                    .build();
        }
    }
}