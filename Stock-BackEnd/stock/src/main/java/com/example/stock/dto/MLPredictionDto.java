package com.example.stock.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MLPredictionDto {
    private String ticker;
    private Double predictedPrice;
    private Double confidenceScore;
    private String trend;
    private Double modelRmse;
    private String detectedPattern;
    private Double patternConfidence;
    private String patternRecommendation;
}