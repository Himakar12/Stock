package com.example.stock.servive;

import com.example.stock.dto.MarketDataResult;
import com.example.stock.dto.TrendingStockDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrendingStocksService {

    private final MarketDataService marketDataService;
    private final OpenRouterService openRouterService;
    private final ObjectMapper objectMapper;

    // Fixed watchlist — Yahoo's free chart endpoint has no "top gainers"
    // screener, so this is "live data on known names", not real discovery.
    private static final Map<String, String> WATCHLIST = new LinkedHashMap<>() {{
        put("NVDA", "NVIDIA Corporation");
        put("PLTR", "Palantir Technologies");
        put("TSLA", "Tesla, Inc.");
        put("AAPL", "Apple Inc.");
        put("MSFT", "Microsoft Corporation");
    }};

    private final Map<String, QuickSnapshot> quickCache = new ConcurrentHashMap<>();
    private final Map<String, RangeSnapshot> rangeCache = new ConcurrentHashMap<>();
    private final Map<String, ThesisSnapshot> thesisCache = new ConcurrentHashMap<>();

    private record QuickSnapshot(double price, String change3Mo, String volume) {}
    private record RangeSnapshot(double high52, double low52) {}
    private record ThesisSnapshot(String sentiment, int confidence, String executiveSummary,
                                   List<String> bullCase, List<String> bearCase,
                                   String keyRisk, String catalyst) {}

    @PostConstruct
    public void initialLoad() {
        log.info("Loading initial trending data for {} tickers...", WATCHLIST.size());
        refreshQuickData();
        refreshRangeAndThesis();
    }

    // Fast-changing numbers — every 1 minute
    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void refreshQuickData() {
        for (String ticker : WATCHLIST.keySet()) {
            try {
                MarketDataResult result = marketDataService.fetchMarketData(ticker);
                quickCache.put(ticker, new QuickSnapshot(
                        result.getLatestData().getClose(),
                        formatChange3Mo(result.getCloses()),
                        formatVolume(result.getLatestData().getVolume())
                ));
            } catch (Exception e) {
                log.warn("Trending quick-data refresh failed for {}: {}", ticker, e.getMessage());
            }
        }
        log.info("Trending quick data refreshed for {} tickers", quickCache.size());
    }

    // Slow-changing 52w range + AI thesis — every 30 minutes
    @Scheduled(fixedRate = 30 * 60_000, initialDelay = 90_000)
    public void refreshRangeAndThesis() {
        for (String ticker : WATCHLIST.keySet()) {
            try {
                double[] range = marketDataService.fetch52WeekRange(ticker);
                rangeCache.put(ticker, new RangeSnapshot(range[0], range[1]));
            } catch (Exception e) {
                log.warn("52-week range refresh failed for {}: {}", ticker, e.getMessage());
            }

            try {
                String systemPrompt = """
                    You are an equity research analyst. Respond in pure JSON only, no markdown, no prose:
                    {
                      "sentiment": "BULLISH|BEARISH|NEUTRAL|STRONG_BUY|STRONG_SELL",
                      "confidence": 82,
                      "executiveSummary": "2-sentence thesis",
                      "bullCase": ["reason 1", "reason 2"],
                      "bearCase": ["risk 1", "risk 2"],
                      "keyRisk": "single biggest risk factor",
                      "catalyst": "the main near-term catalyst driving this stock"
                    }
                    """;
                String userPrompt = "Give a current equity thesis for " + ticker +
                        " (" + WATCHLIST.get(ticker) + ").";

                String raw = openRouterService.callAiWithPrompt(systemPrompt, userPrompt);
                thesisCache.put(ticker, parseThesis(raw));
                log.info("Trending thesis refreshed for {}", ticker);
            } catch (Exception e) {
                log.warn("Trending thesis refresh failed for {} (using fallback): {}", ticker, e.getMessage());
                thesisCache.put(ticker, fallbackThesis(ticker));
            }
        }
    }

    public List<TrendingStockDto> getTrendingStocks() {
        List<TrendingStockDto> out = new ArrayList<>();
        for (String ticker : WATCHLIST.keySet()) {
            QuickSnapshot quick = quickCache.get(ticker);
            if (quick == null) continue; // no data yet — skip rather than send zeros

            RangeSnapshot range = rangeCache.getOrDefault(ticker, new RangeSnapshot(0.0, 0.0));
            ThesisSnapshot thesis = thesisCache.getOrDefault(ticker, fallbackThesis(ticker));

            out.add(TrendingStockDto.builder()
                    .symbol(ticker)
                    .name(WATCHLIST.get(ticker))
                    .price(quick.price())
                    .high52(range.high52())
                    .low52(range.low52())
                    .change3Mo(quick.change3Mo())
                    .volume(quick.volume())
                    .sentiment(thesis.sentiment())
                    .confidence(thesis.confidence())
                    .executiveSummary(thesis.executiveSummary())
                    .bullCase(thesis.bullCase())
                    .bearCase(thesis.bearCase())
                    .keyRisk(thesis.keyRisk())
                    .catalyst(thesis.catalyst())
                    .build());
        }
        return out;
    }

    private ThesisSnapshot parseThesis(String raw) {
        String clean = raw.trim();
        if (clean.startsWith("```json")) clean = clean.substring(7);
        else if (clean.startsWith("```")) clean = clean.substring(3);
        if (clean.endsWith("```")) clean = clean.substring(0, clean.length() - 3);
        clean = clean.trim();

        JsonNode root;
        try {
            root = objectMapper.readTree(clean);
        } catch (Exception e) {
            log.error("Failed to parse trending thesis JSON: {}", raw, e);
            throw new RuntimeException(e);
        }

        List<String> bull = new ArrayList<>();
        root.path("bullCase").forEach(n -> bull.add(n.asText()));
        List<String> bear = new ArrayList<>();
        root.path("bearCase").forEach(n -> bear.add(n.asText()));

        return new ThesisSnapshot(
                root.path("sentiment").asText("NEUTRAL").toUpperCase(),
                root.path("confidence").asInt(50),
                root.path("executiveSummary").asText("Analysis unavailable."),
                bull, bear,
                root.path("keyRisk").asText("Market volatility."),
                root.path("catalyst").asText("General market conditions.")
        );
    }

    private ThesisSnapshot fallbackThesis(String ticker) {
        // Keeps the endpoint alive when OpenRouter's quota is exhausted (like today) —
        // real price data still flows, only the AI-written thesis is a placeholder
        // until refreshRangeAndThesis() succeeds again.
        return new ThesisSnapshot(
                "NEUTRAL", 50,
                ticker + " — live price data active. AI thesis will refresh once quota resets.",
                List.of("Live pricing is up to date."),
                List.of("Qualitative thesis temporarily unavailable."),
                "Data-only mode: no AI risk assessment available right now.",
                "N/A"
        );
    }

    private String formatVolume(long volume) {
        if (volume >= 1_000_000_000) return String.format("%.1fB", volume / 1_000_000_000.0);
        if (volume >= 1_000_000) return String.format("%.1fM", volume / 1_000_000.0);
        if (volume >= 1_000) return String.format("%.1fK", volume / 1_000.0);
        return String.valueOf(volume);
    }

    private String formatChange3Mo(List<Double> closes) {
        if (closes == null || closes.size() < 2) return "+0.0%";
        double first = closes.get(0);
        double last = closes.get(closes.size() - 1);
        if (first == 0) return "+0.0%";
        return String.format("%+.1f%%", ((last - first) / first) * 100);
    }
}