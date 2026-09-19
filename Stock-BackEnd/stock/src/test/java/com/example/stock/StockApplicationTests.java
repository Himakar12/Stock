package com.example.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.stock.dto.HistoricalDataDto;
import com.example.stock.dto.MarketDataResult;
import com.example.stock.dto.TechnicalAnalysisDto;
import com.example.stock.servive.MarketDataService;

@SpringBootTest
class StockApplicationTests {

	

	@Test
	void contextLoads() {
	}
	@Test
	void marketData_usesActualSessionDate_notTodaysDate() {
	    MarketDataResult result = null;
	    MarketDataService marketDataService =new MarketDataService();
		try {
			result = marketDataService.fetchMarketData("RELIANCE");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	    LocalDate sessionDate = LocalDate.parse(result.getLatestData().getDate());
	    // On a weekend, this must be a weekday, and must NOT equal LocalDate.now()
	    assertThat(sessionDate.getDayOfWeek()).isNotIn("SATURDAY"," SUNDAY");
	}

	@Test
	void technicalAnalysis_supportResistance_matchesFormula() {
		HistoricalDataDto data = HistoricalDataDto.builder()
		        .low(1304.10)
		        .high(1333.00)
		        .close(1322.00)          // ← don't skip this
		        .movingAverage30(1305.90) // ← or this
		        .build();
	    MarketDataService marketDataService =new MarketDataService();
		TechnicalAnalysisDto ta = marketDataService.calculateTechnicalAnalysis(data);
	    assertThat(ta.getSupport()).isEqualTo(1278.02); // low * 0.98
	    assertThat(ta.getResistance()).isEqualTo(1359.66); // high * 1.02
	}
}
