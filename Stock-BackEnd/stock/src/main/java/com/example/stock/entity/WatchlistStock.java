package com.example.stock.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "watchlist_stocks", uniqueConstraints = {
        @UniqueConstraint(name = "uk_watchlist_user_symbol", columnNames = {"user_id", "yahoo_symbol"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchlistStock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 30)
    private String ticker;

    @Column(name = "yahoo_symbol", nullable = false, length = 40)
    private String yahooSymbol;

    @Column(length = 120)
    private String displayName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
