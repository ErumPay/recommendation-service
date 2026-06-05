package com.erumpay.recommendation.dto;

import java.util.List;

public record RecommendationStrategyResultResponse(
	String strategyType,
	boolean isBest,
	Long totalBenefitAmount,
	List<RecommendedCardResponse> cards,
	String reason
) {

	public RecommendationStrategyResultResponse(
		String strategyType,
		Long totalBenefitAmount,
		List<RecommendedCardResponse> cards,
		String reason
	) {
		this(strategyType, false, totalBenefitAmount, cards, reason);
	}

	public RecommendationStrategyResultResponse withBest(boolean isBest) {
		return new RecommendationStrategyResultResponse(
			strategyType,
			isBest,
			totalBenefitAmount,
			cards,
			reason
		);
	}
}
