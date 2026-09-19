package com.example.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PredictionDto {
    private Double low;
    private Double high;
    private Integer confidence;
    private String sentiment;
    private String reasoning;
}