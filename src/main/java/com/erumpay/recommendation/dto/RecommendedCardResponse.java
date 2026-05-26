package com.erumpay.recommendation.dto;

import java.util.List;

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
	Long currentPerformanceAmount,
	Long targetPerformanceAmount,
	Long remainingToTarget,
	Long expectedPerformanceAmount,
	Boolean willReachTarget,
	List<String> warnings
) {
}
