package com.example.stock.repository;

import com.example.stock.entity.StockAnalysis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StockAnalysisRepository extends JpaRepository<StockAnalysis, Long> {
    List<StockAnalysis> findTop3ByTickerOrderByCreatedAtDesc(String ticker);
    List<StockAnalysis> findTop3ByTickerAndUserIdOrderByCreatedAtDesc(String ticker, Long userId);
    List<StockAnalysis> findByTickerOrderByCreatedAtDesc(String ticker);
    Page<StockAnalysis> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Page<StockAnalysis> findByUserIdAndTickerIgnoreCaseOrderByCreatedAtDesc(Long userId, String ticker, Pageable pageable);
}
