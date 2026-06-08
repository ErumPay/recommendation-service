package com.erumpay.recommendation.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RecommendationCalculateResponse(
	Long paymentId,
	LocalDateTime recommendedAt,
	List<RecommendationStrategyResultResponse> results,
	String reason
) {
}
