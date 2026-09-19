package com.example.stock.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_analyses", indexes = {
    @Index(name = "idx_ticker_created", columnList = "ticker, createdAt DESC"),
    @Index(name = "idx_user_created", columnList = "userId, createdAt DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(name = "image_path", length = 500)
    private String imagePath;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    @Lob
    @Column(name = "historical_data_json", columnDefinition = "JSON")
    private String historicalDataJson;

    @Lob
    @Column(name = "technical_analysis_json", columnDefinition = "JSON")
    private String technicalAnalysisJson;

    @Lob
    @Column(name = "prediction_json", columnDefinition = "JSON")
    private String predictionJson;

    @Lob
    @Column(name = "prompt_sent", columnDefinition = "LONGTEXT")   // FIXED
    private String promptSent;

    @Lob
    @Column(name = "raw_ai_response", columnDefinition = "LONGTEXT") // FIXED
    private String rawAiResponse;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}