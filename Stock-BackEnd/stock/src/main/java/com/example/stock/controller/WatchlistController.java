package com.example.stock.controller;

import com.example.stock.dto.WatchlistCreateRequest;
import com.example.stock.dto.WatchlistStockDto;
import com.example.stock.servive.WatchlistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/watchlist")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class WatchlistController {
    private final WatchlistService watchlistService;

    @GetMapping
    public ResponseEntity<List<WatchlistStockDto>> getStocks(
            @RequestParam(value = "userId", defaultValue = "1") Long userId) {
        return ResponseEntity.ok(watchlistService.getStocks(userId));
    }

    @PostMapping
    public ResponseEntity<WatchlistStockDto> addStock(
            @RequestParam(value = "userId", defaultValue = "1") Long userId,
            @Valid @RequestBody WatchlistCreateRequest request) {
        return ResponseEntity.status(201).body(watchlistService.addStock(userId, request.getTicker()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStock(
            @RequestParam(value = "userId", defaultValue = "1") Long userId,
            @PathVariable Long id) {
        watchlistService.deleteStock(userId, id);
        return ResponseEntity.noContent().build();
    }
}
