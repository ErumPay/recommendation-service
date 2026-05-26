package com.erumpay.recommendation.dto;

public record BenefitSingleRecommendationRequest(
	Long userId,
	String merchantName,
	String mccCode,
	Long amount
) {
}
