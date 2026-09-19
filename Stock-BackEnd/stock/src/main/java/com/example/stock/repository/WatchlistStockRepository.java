package com.example.stock.repository;

import com.example.stock.entity.WatchlistStock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WatchlistStockRepository extends JpaRepository<WatchlistStock, Long> {
    List<WatchlistStock> findTop5ByUserIdOrderByCreatedAtAsc(Long userId);
    boolean existsByUserIdAndYahooSymbolIgnoreCase(Long userId, String yahooSymbol);
    long countByUserId(Long userId);
}
