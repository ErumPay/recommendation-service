package com.erumpay.recommendation.dto;

import java.util.List;

public record BenefitSingleRecommendationResponse(
	String strategyType,
	Long totalBenefitAmount,
	List<RecommendedCardResponse> cards,
	String reason
) {
}
