package com.example.stock.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisHistoryDto {
    private Long id;
    private Long userId;
    private String ticker;
    private Integer confidence;
    private PredictionDto prediction;
    private HistoricalDataDto historicalData;
    private TechnicalAnalysisDto technicalAnalysis;
    private LocalDateTime createdAt;
}
