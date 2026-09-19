package com.example.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendingStockDto {
    private String symbol;
    private String name;
    private double price;
    private double high52;
    private double low52;
    private String change3Mo;
    private String volume;
    private String sentiment;
    private int confidence;
    private String executiveSummary;
    private List<String> bullCase;
    private List<String> bearCase;
    private String keyRisk;
    private String catalyst;
}