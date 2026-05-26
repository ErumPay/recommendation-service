package com.erumpay.recommendation.dto;

public record PerfSingleRecommendationRequest(
	Long userId,
	String merchantName,
	String mccCode,
	Long amount
) {
}
