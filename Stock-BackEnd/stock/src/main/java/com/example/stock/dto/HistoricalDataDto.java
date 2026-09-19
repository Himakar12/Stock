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
public class HistoricalDataDto {
    private String ticker;
    private String date;
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long volume;
    private Double previousClose;
    private Double movingAverage30;
    private List<Double> last7Days;
    private String marketStatus; // REGULAR, PRE, POST, or CLOSED
    private String asOf;         // ISO timestamp of this exact price snapshot
}