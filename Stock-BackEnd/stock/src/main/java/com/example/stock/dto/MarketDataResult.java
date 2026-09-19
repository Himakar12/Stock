package com.example.stock.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketDataResult {
    private HistoricalDataDto latestData;   // Single-day snapshot for the prompt
    private List<Double> closes;            // Full history for LSTM
    private List<Double> lows;              // Full history for pattern detection
    private List<Double> highs;
    private List<Double> opens;
    private List<Long> volumes;
}