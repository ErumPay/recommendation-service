package com.erumpay.recommendation.service;

import com.erumpay.recommendation.client.CardServiceClient;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.exception.CardServiceClientException;
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

	// [be] 이준혁 260526 1104 | card-service 장애와 계약 오류를 분리해 재시도 가능한 실패인지 구분한다.
	public CardRecommendationSourceResponse getRecommendationSource(Long userId, String yearMonth) {
		try {
			CardRecommendationSourceResponse response = cardServiceClient.getRecommendationSource(userId, yearMonth);
			if (response == null) {
				throw new CardServiceClientException(
					new IllegalStateException("card-service recommendation-source response is required")
				);
			}
			return response;
		} catch (FeignException exception) {
			if (isServiceUnavailable(exception.status())) {
				throw new CardServiceUnavailableException(exception);
			}
			throw new CardServiceClientException(exception);
		}
	}

	private boolean isServiceUnavailable(int status) {
		return status <= 0 || status >= 500;
	}

	private String previousYearMonth() {
		return YearMonth.now(clock)
			.minusMonths(1)
			.format(YEAR_MONTH_FORMATTER);
	}
}
