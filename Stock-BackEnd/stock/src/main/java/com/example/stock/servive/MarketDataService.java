package com.example.stock.servive;

import com.example.stock.dto.HistoricalDataDto;
import com.example.stock.dto.MarketDataResult;
import com.example.stock.dto.TechnicalAnalysisDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class MarketDataService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String YAHOO_CHART_URL =
        "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=3mo";

    public MarketDataResult fetchMarketData(String ticker) {
        String cleanTicker = ticker.trim().toUpperCase();
        log.info("Fetching market data for: {}", cleanTicker);

        try {
            return fetchFromYahoo(cleanTicker);
        } catch (HttpClientErrorException.NotFound e) {
            if (cleanTicker.endsWith(".NS")) {
                throw new RuntimeException("Could not fetch market data for " + cleanTicker + ": " + e.getMessage(), e);
            }
            String nsTicker = cleanTicker + ".NS";
            log.info("{} not found on Yahoo — retrying as NSE ticker: {}", cleanTicker, nsTicker);
            try {
                return fetchFromYahoo(nsTicker);
            } catch (Exception retryEx) {
                throw new RuntimeException(
                        "Could not fetch market data for " + cleanTicker + " (also tried " + nsTicker + "): "
                                + retryEx.getMessage(), retryEx);
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch market data for " + cleanTicker + ": " + e.getMessage(), e);
        }
    }

    private MarketDataResult fetchFromYahoo(String ticker) throws JsonMappingException, JsonProcessingException {
        String url = String.format(YAHOO_CHART_URL, ticker);

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
            url, HttpMethod.GET, entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode result = root.path("chart").path("result").get(0);

        if (result == null || result.isMissingNode()) {
            throw new RuntimeException("No data returned for ticker: " + ticker);
        }

        // NEW: read live market status straight from Yahoo's own meta block —
        // this is Yahoo's authoritative signal for whether trading is live
        // right now, rather than us trying to compute exchange hours ourselves.
        JsonNode meta = result.path("meta");
        String marketState = meta.path("marketState").asText("CLOSED"); // REGULAR, PRE, POST, CLOSED
        boolean isLive = "REGULAR".equals(marketState);

        long epochSec = meta.path("regularMarketTime").asLong(0);
        String tzName = meta.path("exchangeTimezoneName").asText(null);
        String asOf = null;
        if (epochSec > 0) {
            ZoneId zone = (tzName != null) ? ZoneId.of(tzName) : ZoneOffset.UTC;
            asOf = Instant.ofEpochSecond(epochSec).atZone(zone).toString();
        }

        JsonNode timestamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").get(0);
        JsonNode closesNode = quote.path("close");
        JsonNode opensNode = quote.path("open");
        JsonNode highsNode = quote.path("high");
        JsonNode lowsNode = quote.path("low");
        JsonNode volumesNode = quote.path("volume");

        List<Double> closes = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        List<Double> highs = new ArrayList<>();
        List<Double> opens = new ArrayList<>();
        List<Long> volumes = new ArrayList<>();

        for (int i = 0; i < timestamps.size(); i++) {
            if (!closesNode.get(i).isNull()
                    && !opensNode.get(i).isNull()
                    && !highsNode.get(i).isNull()
                    && !lowsNode.get(i).isNull()
                    && !volumesNode.get(i).isNull()) {
                closes.add(closesNode.get(i).asDouble());
                lows.add(lowsNode.get(i).asDouble());
                highs.add(highsNode.get(i).asDouble());
                opens.add(opensNode.get(i).asDouble());
                volumes.add(volumesNode.get(i).asLong());
            }
        }

        if (closes.isEmpty()) {
            throw new RuntimeException("No usable (non-null) OHLCV rows for ticker: " + ticker);
        }

        int lastIdx = closes.size() - 1;
        int prevIdx = Math.max(0, lastIdx - 1);

        double latestClose = closes.get(lastIdx);
        double latestHigh = highs.get(lastIdx);
        double latestLow = lows.get(lastIdx);

        // NEW: while the market is genuinely open, meta's live fields are
        // fresher than the array's last bar, which can lag behind the
        // interval boundary. Override with them when present.
        if (isLive) {
            if (meta.hasNonNull("regularMarketPrice")) {
                latestClose = meta.path("regularMarketPrice").asDouble(latestClose);
            }
            if (meta.hasNonNull("regularMarketDayHigh")) {
                latestHigh = meta.path("regularMarketDayHigh").asDouble(latestHigh);
            }
            if (meta.hasNonNull("regularMarketDayLow")) {
                latestLow = meta.path("regularMarketDayLow").asDouble(latestLow);
            }
        }

        double latestOpen = opens.get(lastIdx);
        long latestVolume = volumes.get(lastIdx);
        double previousClose = closes.get(prevIdx);

        List<Double> last7Days = new ArrayList<>();
        int start = Math.max(0, closes.size() - 7);
        for (int i = start; i <= lastIdx; i++) {
            last7Days.add(round2(closes.get(i)));
        }

        int maStart = Math.max(0, closes.size() - 30);
        double sum = 0;
        for (int i = maStart; i <= lastIdx; i++) {
            sum += closes.get(i);
        }
        double ma30 = sum / (lastIdx - maStart + 1);

        HistoricalDataDto latestData = HistoricalDataDto.builder()
            .ticker(ticker)
            .date(asOf != null ? asOf.substring(0, 10) : LocalDate.now().toString())
            .open(round2(latestOpen))
            .high(round2(latestHigh))
            .low(round2(latestLow))
            .close(round2(latestClose))
            .volume(latestVolume)
            .previousClose(round2(previousClose))
            .movingAverage30(round2(ma30))
            .last7Days(last7Days)
            .marketStatus(marketState)
            .asOf(asOf)
            .build();

        return MarketDataResult.builder()
            .latestData(latestData)
            .closes(closes)
            .lows(lows)
            .highs(highs)
            .opens(opens)
            .volumes(volumes)
            .build();
    }

    public double[] fetch52WeekRange(String ticker) {
        String cleanTicker = ticker.trim().toUpperCase();
        try {
            return fetch52wFromYahoo(cleanTicker);
        } catch (HttpClientErrorException.NotFound e) {
            if (!cleanTicker.endsWith(".NS")) {
                try {
                    return fetch52wFromYahoo(cleanTicker + ".NS");
                } catch (Exception ignored) {
                    return new double[]{0.0, 0.0};
                }
            }
            return new double[]{0.0, 0.0};
        } catch (Exception e) {
            log.warn("52-week range fetch failed for {}: {}", ticker, e.getMessage());
            return new double[]{0.0, 0.0};
        }
    }

    private double[] fetch52wFromYahoo(String ticker) throws JsonProcessingException {
        String url = String.format(
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=1y", ticker);

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode result = root.path("chart").path("result").get(0);
        JsonNode quote = result.path("indicators").path("quote").get(0);
        JsonNode highsNode = quote.path("high");
        JsonNode lowsNode = quote.path("low");

        double max = Double.MIN_VALUE, min = Double.MAX_VALUE;
        for (int i = 0; i < highsNode.size(); i++) {
            if (!highsNode.get(i).isNull()) max = Math.max(max, highsNode.get(i).asDouble());
            if (!lowsNode.get(i).isNull()) min = Math.min(min, lowsNode.get(i).asDouble());
        }
        return new double[]{ max == Double.MIN_VALUE ? 0.0 : max, min == Double.MAX_VALUE ? 0.0 : min };
    }

    public TechnicalAnalysisDto calculateTechnicalAnalysis(HistoricalDataDto data) {
        double support = data.getLow() * 0.98;
        double resistance = data.getHigh() * 1.02;
        String trend = data.getClose() > data.getMovingAverage30() ? "bullish" : "bearish";

        double change7d = 0;
        List<Double> days = data.getLast7Days();
        if (days != null && days.size() >= 2) {
            double first = days.get(0);
            double last = days.get(days.size() - 1);
            change7d = ((last - first) / first) * 100;
        }

        List<String> signals = new ArrayList<>();
        signals.add(data.getClose() > data.getMovingAverage30() ? "Price above 30-MA" : "Price below 30-MA");
        signals.add(data.getVolume() != null ? "Volume: " + data.getVolume() : "Volume unavailable");
        if (days != null && days.size() >= 3
                && days.get(days.size() - 1) > days.get(days.size() - 2)
                && days.get(days.size() - 2) > days.get(days.size() - 3)) {
            signals.add("Higher highs pattern");
        }

        return TechnicalAnalysisDto.builder()
            .trend(trend)
            .movingAverage30(data.getMovingAverage30())
            .support(round2(support))
            .resistance(round2(resistance))
            .change7d(String.format("%+.2f%%", change7d))
            .volumeSignal(data.getVolume() != null && data.getVolume() > 40_000_000 ? "above_average" : "normal")
            .signals(signals)
            .build();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}