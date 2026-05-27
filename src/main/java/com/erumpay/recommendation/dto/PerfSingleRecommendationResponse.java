package com.erumpay.recommendation.dto;

import java.util.List;

public record PerfSingleRecommendationResponse(
	String strategyType,
	Long totalBenefitAmount,
	List<RecommendedCardResponse> cards,
	String reason
) {
}
