package com.example.stock.servive;

import com.example.stock.dto.DebateResult;
import com.example.stock.dto.HistoricalDataDto;
import com.example.stock.dto.MLPredictionDto;
import com.example.stock.dto.TechnicalAnalysisDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DebateOrchestratorService {

    private final AiService aiService;              // Gemini - ML advocate
    private final SecondAiService secondAiService;   // Groq - technical advocate
    private final OpenRouterService openRouterService; // Neutral judge - must be a THIRD voice,
                                                         // never one of the two advocates above

    private static final double DISAGREEMENT_THRESHOLD = 0.03;

    public DebateResult runDebate(String ticker, HistoricalDataDto data,
                                      TechnicalAnalysisDto ta, MLPredictionDto ml) {

        double technicalMidpoint = (ta.getSupport() + ta.getResistance()) / 2.0;
        double priceGap = Math.abs(ml.getPredictedPrice() - technicalMidpoint) / technicalMidpoint;
        boolean trendConflict = !ml.getTrend().equalsIgnoreCase(ta.getTrend());

        double disagreementScore = priceGap + (trendConflict ? 0.05 : 0.0);
        boolean shouldDebate = disagreementScore >= DISAGREEMENT_THRESHOLD;

        log.info("Disagreement score for {}: {} (debate triggered: {})", ticker, disagreementScore, shouldDebate);

        if (!shouldDebate) {
            return DebateResult.builder()
                    .debateTriggered(false)
                    .disagreementScore(disagreementScore)
                    .build();
        }

        String mlCase = aiService.callAiWithPrompt(
            "You are a quant who trusts machine learning models over classical technical analysis. " +
            "Argue in under 80 words why the LSTM forecast should be trusted here.",
            String.format("Ticker: %s. LSTM predicts $%.2f (confidence %.1f%%, RMSE %.4f). " +
                          "Technical analysis says support $%.2f, resistance $%.2f, trend %s. " +
                          "Make the case for the ML prediction.",
                ticker, ml.getPredictedPrice(), ml.getConfidenceScore(), ml.getModelRmse(),
                ta.getSupport(), ta.getResistance(), ta.getTrend())
        );

        String technicalCase = secondAiService.callAiWithPrompt(
            "You are a veteran technical analyst skeptical of black-box ML forecasts. " +
            "Argue in under 80 words why the technical levels should be trusted here.",
            String.format("Ticker: %s. Technical analysis: support $%.2f, resistance $%.2f, trend %s. " +
                          "The LSTM model disagrees, predicting $%.2f. Make the case for trusting technicals instead.",
                ticker, ta.getSupport(), ta.getResistance(), ta.getTrend(), ml.getPredictedPrice())
        );

        String judgeSystemPrompt =
            "You are a neutral quant arbitrator. You did not write either argument below. " +
            "Decide which signal deserves more weight for a short-term price call, using: " +
            "(1) ML confidence and RMSE — low RMSE and high confidence favor ML; " +
            "(2) trend agreement — if the broader trend backs one side, weight it higher; " +
            "(3) recency — technical levels react faster to new price action than a trained LSTM. " +
            "Respond with ONLY this JSON, no prose outside it: " +
            "{\"verdict\":\"ML_FAVORED|TECHNICAL_FAVORED|BALANCED\"," +
            "\"finalPriceTarget\":<number>,\"confidenceScore\":<0-100>," +
            "\"reasoning\":\"<max 40 words>\"}";

        String judgeUserPrompt = String.format(
            "ML case: \"%s\"\nTechnical case: \"%s\"\nML predicted: $%.2f. Technical range: $%.2f-$%.2f.",
            mlCase, technicalCase, ml.getPredictedPrice(), ta.getSupport(), ta.getResistance()
        );

        // Judge must be openRouterService (a genuinely independent third model) —
        // NOT secondAiService, which already argued the technical side above and
        // would otherwise be judging its own advocacy.
        String synthesis;
        try {
            synthesis = openRouterService.callAiWithPrompt(judgeSystemPrompt, judgeUserPrompt);
        } catch (Exception e) {
            // OpenRouter can fail (dead model slug, rate limit, no credits, etc.).
            // Degrade to Gemini as judge rather than falling back to Groq, which
            // would reintroduce the exact self-judging problem this fix removes.
            log.warn("OpenRouter judge call failed ({}); falling back to Gemini as judge.", e.getMessage());
            synthesis = aiService.callAiWithPrompt(judgeSystemPrompt, judgeUserPrompt);
        }

        return DebateResult.builder()
                .debateTriggered(true)
                .disagreementScore(disagreementScore)
                .mlAdvocatePosition(mlCase)
                .technicalAdvocatePosition(technicalCase)
                .finalSynthesis(synthesis)
                .build();
    }
}