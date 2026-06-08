package com.erumpay.recommendation.dto;

import java.util.List;

public record BenefitSplitRecommendationResponse(
	String strategyType,
	Long totalBenefitAmount,
	List<RecommendedCardResponse> cards,
	String reason
) {
}
