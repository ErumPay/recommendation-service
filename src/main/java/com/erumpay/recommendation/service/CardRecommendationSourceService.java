package com.erumpay.recommendation.service;

import com.erumpay.recommendation.client.CardServiceClient;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.exception.CardServiceUnavailableException;
import feign.FeignException;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CardRecommendationSourceService {

	private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

	private final CardServiceClient cardServiceClient;
	private final Clock clock;

	// [be] 이준혁 260526 1104 | 추천 계산 시각 기준 전월을 산출해 card-service 추천 소스 API를 조회한다.
	public CardRecommendationSourceResponse getRecommendationSource(Long userId) {
		return getRecommendationSource(userId, previousYearMonth());
	}

	// [be] 이준혁 260526 1104 | card-service 장애 시 부정확한 추천을 만들지 않고 상위 흐름에서 503으로 응답하게 한다.
	public CardRecommendationSourceResponse getRecommendationSource(Long userId, String yearMonth) {
		try {
			return cardServiceClient.getRecommendationSource(userId, yearMonth);
		} catch (FeignException exception) {
			throw new CardServiceUnavailableException(exception);
		}
	}

	private String previousYearMonth() {
		return YearMonth.now(clock)
			.minusMonths(1)
			.format(YEAR_MONTH_FORMATTER);
	}
}
