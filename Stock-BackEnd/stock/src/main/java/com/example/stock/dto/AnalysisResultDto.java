package com.example.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalysisResultDto {
    private String ticker;
    private Integer confidence;
    private HistoricalDataDto historicalData;
    private TechnicalAnalysisDto analysis;
    private PredictionDto prediction;
    private MLPredictionDto mlPrediction;
    private StockAiAgentDto aiInsights;
    private DebateResult debateResult;
}




