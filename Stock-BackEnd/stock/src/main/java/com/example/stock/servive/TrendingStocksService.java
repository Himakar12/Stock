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
    private static final long DEFAULT_USER_ID = 1L;

    private final MarketDataService marketDataService;
    private final OpenRouterService openRouterService;
    private final ObjectMapper objectMapper;
    private final WatchlistService watchlistService;

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
        refreshQuickData();
        refreshRangeAndThesis();
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void refreshQuickData() {
        for (String ticker : configuredTickers()) {
            try {
                MarketDataResult result = marketDataService.fetchMarketData(ticker);
                quickCache.put(ticker, new QuickSnapshot(result.getLatestData().getClose(),
                        formatChange3Mo(result.getCloses()), formatVolume(result.getLatestData().getVolume())));
            } catch (Exception e) {
                log.warn("Trending quick-data refresh failed for {}: {}", ticker, e.getMessage());
            }
        }
    }

    @Scheduled(fixedRate = 30 * 60_000, initialDelay = 90_000)
    public void refreshRangeAndThesis() {
        for (String ticker : configuredTickers()) {
            try {
                double[] range = marketDataService.fetch52WeekRange(ticker);
                rangeCache.put(ticker, new RangeSnapshot(range[0], range[1]));
            } catch (Exception e) {
                log.warn("52-week range refresh failed for {}: {}", ticker, e.getMessage());
            }
            thesisCache.putIfAbsent(ticker, fallbackThesis(ticker));
        }
    }

    public List<TrendingStockDto> getTrendingStocks() {
        List<TrendingStockDto> output = new ArrayList<>();
        for (String ticker : configuredTickers()) {
            QuickSnapshot quick = quickCache.get(ticker);
            if (quick == null) continue;
            RangeSnapshot range = rangeCache.getOrDefault(ticker, new RangeSnapshot(0, 0));
            ThesisSnapshot thesis = thesisCache.getOrDefault(ticker, fallbackThesis(ticker));
            output.add(TrendingStockDto.builder()
                    .symbol(ticker).name(ticker).price(quick.price())
                    .high52(range.high52()).low52(range.low52()).change3Mo(quick.change3Mo())
                    .volume(quick.volume()).sentiment(thesis.sentiment()).confidence(thesis.confidence())
                    .executiveSummary(thesis.executiveSummary()).bullCase(thesis.bullCase())
                    .bearCase(thesis.bearCase()).keyRisk(thesis.keyRisk()).catalyst(thesis.catalyst()).build());
        }
        return output;
    }

    private List<String> configuredTickers() {
        return watchlistService.getStocks(DEFAULT_USER_ID).stream()
                .map(item -> item.getYahooSymbol()).limit(5).toList();
    }

    private ThesisSnapshot fallbackThesis(String ticker) {
        return new ThesisSnapshot("NEUTRAL", 50, ticker + " — live price data active.",
                List.of("Live pricing is available."), List.of("Qualitative thesis unavailable."),
                "Market volatility.", "N/A");
    }

    private String formatVolume(long volume) {
        if (volume >= 1_000_000_000) return String.format("%.1fB", volume / 1_000_000_000.0);
        if (volume >= 1_000_000) return String.format("%.1fM", volume / 1_000_000.0);
        if (volume >= 1_000) return String.format("%.1fK", volume / 1_000.0);
        return String.valueOf(volume);
    }

    private String formatChange3Mo(List<Double> closes) {
        if (closes == null || closes.size() < 2) return "+0.0%";
        double first = closes.get(0), last = closes.get(closes.size() - 1);
        return first == 0 ? "+0.0%" : String.format("%+.1f%%", ((last - first) / first) * 100);
    }
}
