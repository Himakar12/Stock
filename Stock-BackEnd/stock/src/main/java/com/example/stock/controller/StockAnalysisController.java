
package com.example.stock.controller;

import com.example.stock.dto.AnalysisResultDto;
import com.example.stock.servive.StockAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/analyze")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200") // Angular dev server
public class StockAnalysisController {

    private final StockAnalysisService analysisService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalysisResultDto> analyzeStock(
            @RequestParam("chartImage") MultipartFile chartImage,
            @RequestParam(value = "userId", required = false, defaultValue = "1") Long userId) {
        
        AnalysisResultDto result = analysisService.analyzeStock(chartImage, userId);
        return ResponseEntity.ok(result);
    }
}