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
public class StockAiAgentDto {
    private String sentiment;              // BULLISH, BEARISH, NEUTRAL
    private String executiveSummary;       // Concise overall thesis
    private List<String> bullCase;         // Arguments for upside
    private List<String> bearCase;         // Risks and downside factors
    private List<String> keyRisks;         // Critical risk factors to monitor
    private String valuationAssessment;    // Valuation perspective based on metrics
    private String agentModel;             // The AI model used
    private Long timestamp;                // Generated timestamp
}
