package com.example.stock.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistStockDto {
    private Long id;
    private Long userId;
    private String ticker;
    private String yahooSymbol;
    private String displayName;
    private Double price;
    private Double high52;
    private Double low52;
    private String marketStatus;
    private String asOf;
    private String errorMessage;
    private LocalDateTime createdAt;
}
