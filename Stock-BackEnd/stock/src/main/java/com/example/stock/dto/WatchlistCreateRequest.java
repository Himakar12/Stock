package com.example.stock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WatchlistCreateRequest {
    @NotBlank
    @Size(max = 30)
    private String ticker;
}
