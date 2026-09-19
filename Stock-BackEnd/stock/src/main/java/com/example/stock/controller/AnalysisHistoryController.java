package com.example.stock.controller;

import com.example.stock.dto.AnalysisHistoryDto;
import com.example.stock.servive.AnalysisHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class AnalysisHistoryController {
    private final AnalysisHistoryService historyService;

    @GetMapping
    public ResponseEntity<Page<AnalysisHistoryDto>> getHistory(
            @RequestParam(value = "userId", defaultValue = "1") Long userId,
            @RequestParam(required = false) String ticker,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return ResponseEntity.ok(historyService.findHistory(userId, ticker, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AnalysisHistoryDto> getHistoryItem(
            @RequestParam(value = "userId", defaultValue = "1") Long userId,
            @PathVariable Long id) {
        return ResponseEntity.ok(historyService.findById(userId, id));
    }
}
