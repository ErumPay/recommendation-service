package com.erumpay.recommendation.dto;

import java.util.List;

public record BenefitSingleRecommendationResponse(
	String strategyType,
	Long totalBenefitAmount,
	List<RecommendedCardResponse> cards,
	String reason
) {

	public record RecommendedCardResponse(
		Long cardId,
		Long cardProductId,
		String cardCompany,
		String cardName,
		String maskedNumber,
		Long amount,
		Long discountAmount,
		Long cashbackAmount,
		Long mileageAmount,
		Long totalBenefitAmount,
		List<String> warnings
	) {
	}
}
