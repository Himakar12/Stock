package com.example.stock.servive;

import com.example.stock.dto.MarketDataResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MarketDataCacheService {

    private static final String CACHE_KEY_PREFIX = "market:data:";
    private static final long CACHE_TTL_SECONDS = 600; // 10 minutes

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public MarketDataCacheService(RedisTemplate<String, String> redisTemplate,
                                  ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public MarketDataResult get(String ticker) {
        try {
            String key = CACHE_KEY_PREFIX + ticker;
            String cachedValue = redisTemplate.opsForValue().get(key);

            if (cachedValue == null || cachedValue.isBlank()) {
                return null;
            }

            MarketDataResult result = objectMapper.readValue(cachedValue, MarketDataResult.class);
            log.info("Cache HIT for ticker: {}", ticker);
            return result;
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached market data for ticker: {}", ticker, e);
            return null;
        } catch (Exception e) {
            log.error("Redis cache read failed for ticker: {}", ticker, e);
            return null;
        }
    }

    public void put(String ticker, MarketDataResult result) {
        try {
            String key = CACHE_KEY_PREFIX + ticker;
            String serializedValue = objectMapper.writeValueAsString(result);

            redisTemplate.opsForValue().set(key, serializedValue, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            log.info("Cache PUT for ticker: {} (TTL: {} seconds)", ticker, CACHE_TTL_SECONDS);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize market data for cache, ticker: {}", ticker, e);
        } catch (Exception e) {
            log.error("Redis cache write failed for ticker: {}", ticker, e);
        }
    }

    public void evict(String ticker) {
        try {
            redisTemplate.delete(CACHE_KEY_PREFIX + ticker);
            log.info("Cache EVICT for ticker: {}", ticker);
        } catch (Exception e) {
            log.error("Redis cache eviction failed for ticker: {}", ticker, e);
        }
    }
}
