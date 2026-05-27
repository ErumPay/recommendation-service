package com.erumpay.recommendation.client;

import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "card-service", url = "${CARD_SERVICE_BASE_URL:http://localhost:8082}")
public interface CardServiceClient {

	// [be] 이준혁 260526 1104 | 추천 계산에 필요한 사용자 카드, 실적, 혜택 데이터를 card-service 내부 API에서 조회한다.
	@GetMapping("/internal/v1/cards/users/{userId}/recommendation-source")
	CardRecommendationSourceResponse getRecommendationSource(
		@PathVariable("userId") Long userId,
		@RequestParam("yearMonth") String yearMonth
	);
}
