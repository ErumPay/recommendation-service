package com.erumpay.recommendation.dto;

import java.math.BigDecimal;
import java.util.List;

public record CardRecommendationSourceResponse(
	Long userId,
	String yearMonth,
	List<CardRecommendationSourceCardResponse> cards
) {

	public record CardRecommendationSourceCardResponse(
		Long cardId,
		Long cardProductId,
		String cardCompany,
		String cardName,
		String maskedNumber,
		Boolean isDefault,
		Long performanceAmount,
		List<CardBenefitResponse> benefits
	) {
	}

	public record CardBenefitResponse(
		Long benefitId,
		String serviceCategory,
		String benefitType,
		Long minAmount,
		String timeStart,
		String timeEnd,
		String dayCondition,
		String benefitDesc,
		List<String> brandNames,
		List<CardBenefitTierResponse> tiers
	) {
	}

	public record CardBenefitTierResponse(
		Long tierId,
		Long minPrevMonthUsage,
		Long maxPrevMonthUsage,
		BigDecimal rate,
		Long flatAmount,
		Long maxBenefitPerUse,
		Integer dailyLimitCount,
		Long dailyLimitAmount,
		Integer monthlyLimitCount,
		Long monthlyLimitAmount,
		Integer yearlyLimitCount,
		Long yearlyLimitAmount,
		String tierDesc
	) {
	}
}
