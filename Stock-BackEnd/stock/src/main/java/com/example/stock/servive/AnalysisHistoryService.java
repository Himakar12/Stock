package com.example.stock.servive;

import com.example.stock.dto.*;
import com.example.stock.entity.StockAnalysis;
import com.example.stock.repository.StockAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisHistoryService {
    private final StockAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    public Page<AnalysisHistoryDto> findHistory(Long userId, String ticker, Pageable pageable) {
        Page<StockAnalysis> page = ticker == null || ticker.isBlank()
                ? repository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                : repository.findByUserIdAndTickerIgnoreCaseOrderByCreatedAtDesc(userId, ticker.trim(), pageable);
        return page.map(this::toDto);
    }

    public AnalysisHistoryDto findById(Long userId, Long id) {
        StockAnalysis analysis = repository.findById(id)
                .filter(item -> userId.equals(item.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("Analysis record not found"));
        return toDto(analysis);
    }

    private AnalysisHistoryDto toDto(StockAnalysis item) {
        return AnalysisHistoryDto.builder()
                .id(item.getId())
                .userId(item.getUserId())
                .ticker(item.getTicker())
                .confidence(item.getConfidenceScore())
                .prediction(read(item.getPredictionJson(), PredictionDto.class))
                .historicalData(read(item.getHistoricalDataJson(), HistoricalDataDto.class))
                .technicalAnalysis(read(item.getTechnicalAnalysisJson(), TechnicalAnalysisDto.class))
                .createdAt(item.getCreatedAt())
                .build();
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, type); }
        catch (Exception ignored) { return null; }
    }
}
