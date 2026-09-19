package com.example.stock.repository;

import com.example.stock.entity.StockAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StockAnalysisRepository extends JpaRepository<StockAnalysis, Long> {
    
    // Fetch last 3 analyses for this ticker to build context
    List<StockAnalysis> findTop3ByTickerOrderByCreatedAtDesc(String ticker);
    
    // If you have users:
    List<StockAnalysis> findTop3ByTickerAndUserIdOrderByCreatedAtDesc(String ticker, Long userId);
    
    // Fetch all analyses for a ticker (for history page)
    List<StockAnalysis> findByTickerOrderByCreatedAtDesc(String ticker);
}