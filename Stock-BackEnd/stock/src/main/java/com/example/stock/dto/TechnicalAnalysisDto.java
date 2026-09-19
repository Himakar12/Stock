package com.example.stock.dto;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TechnicalAnalysisDto {
    private String trend;
    private Double movingAverage30;
    private Double support;
    private Double resistance;
    private String change7d;
    private String volumeSignal;
    private List<String> signals;
}
