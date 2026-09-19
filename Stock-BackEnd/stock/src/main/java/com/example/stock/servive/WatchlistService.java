package com.example.stock.servive;

import com.example.stock.dto.*;
import com.example.stock.entity.WatchlistStock;
import com.example.stock.repository.WatchlistStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class WatchlistService {
    private static final int MAX_STOCKS = 5;

    private final WatchlistStockRepository repository;
    private final MarketDataService marketDataService;

    @Transactional(readOnly = true)
    public List<WatchlistStockDto> getStocks(Long userId) {
        return repository.findTop5ByUserIdOrderByCreatedAtAsc(userId)
                .stream()
                .map(this::toDtoWithMarketData)
                .toList();
    }

    @Transactional
    public WatchlistStockDto addStock(Long userId, String rawTicker) {
        String input = normalize(rawTicker);
        if (!input.matches("[A-Z0-9.\\-]{1,30}")) {
            throw new IllegalArgumentException("Ticker contains unsupported characters");
        }
        if (repository.countByUserId(userId) >= MAX_STOCKS) {
            throw new IllegalStateException("Watchlist supports a maximum of " + MAX_STOCKS + " stocks");
        }

        MarketDataResult marketData = marketDataService.fetchMarketData(input);
        HistoricalDataDto snapshot = marketData.getLatestData();
        String yahooSymbol = snapshot.getTicker().toUpperCase(Locale.ROOT);
        if (repository.existsByUserIdAndYahooSymbolIgnoreCase(userId, yahooSymbol)) {
            throw new IllegalStateException("Stock is already in the watchlist");
        }

        WatchlistStock saved = repository.save(WatchlistStock.builder()
                .userId(userId)
                .ticker(input)
                .yahooSymbol(yahooSymbol)
                .displayName(yahooSymbol)
                .build());
        return toDto(saved, snapshot, marketDataService.fetch52WeekRange(yahooSymbol));
    }

    @Transactional
    public void deleteStock(Long userId, Long id) {
        WatchlistStock stock = repository.findById(id)
                .filter(item -> userId.equals(item.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("Watchlist stock not found"));
        repository.delete(stock);
    }

    private WatchlistStockDto toDtoWithMarketData(WatchlistStock stock) {
        try {
            MarketDataResult result = marketDataService.fetchMarketData(stock.getYahooSymbol());
            return toDto(stock, result.getLatestData(), marketDataService.fetch52WeekRange(stock.getYahooSymbol()));
        } catch (Exception e) {
            return WatchlistStockDto.builder()
                    .id(stock.getId()).userId(stock.getUserId()).ticker(stock.getTicker())
                    .yahooSymbol(stock.getYahooSymbol()).displayName(stock.getDisplayName())
                    .createdAt(stock.getCreatedAt()).errorMessage("Market data unavailable")
                    .build();
        }
    }

    private WatchlistStockDto toDto(WatchlistStock stock, HistoricalDataDto data, double[] range) {
        return WatchlistStockDto.builder()
                .id(stock.getId()).userId(stock.getUserId()).ticker(stock.getTicker())
                .yahooSymbol(stock.getYahooSymbol()).displayName(stock.getDisplayName())
                .price(data.getClose()).high52(range[0]).low52(range[1])
                .marketStatus(data.getMarketStatus()).asOf(data.getAsOf())
                .createdAt(stock.getCreatedAt())
                .build();
    }

    private String normalize(String rawTicker) {
        if (rawTicker == null || rawTicker.isBlank()) {
            throw new IllegalArgumentException("Ticker is required");
        }
        return rawTicker.trim().toUpperCase(Locale.ROOT);
    }
}
