package com.example.stock.controller;

import com.example.stock.dto.TrendingStockDto;
import com.example.stock.servive.TrendingStocksService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/trending")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class TrendingStocksController {

    private final TrendingStocksService trendingStocksService;

    @GetMapping
    public ResponseEntity<List<TrendingStockDto>> getTrendingStocks() {
        return ResponseEntity.ok(trendingStocksService.getTrendingStocks());
    }
}
