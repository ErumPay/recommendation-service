package com.erumpay.recommendation.dto;

import java.util.List;

public record PerfSplitRecommendationResponse(
	String strategyType,
	Long totalBenefitAmount,
	List<RecommendedCardResponse> cards,
	String reason
) {
}
