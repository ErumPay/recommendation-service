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
		String imageUrl,
		String maskedNumber,
		Boolean isDefault,
		Long performanceAmount,
		Long previousMonthPerformanceAmount,
		Long currentMonthPerformanceAmount,
		List<CardBenefitResponse> benefits
	) {

		public CardRecommendationSourceCardResponse(
			Long cardId,
			Long cardProductId,
			String cardCompany,
			String cardName,
			String imageUrl,
			String maskedNumber,
			Boolean isDefault,
			Long performanceAmount,
			List<CardBenefitResponse> benefits
		) {
			this(
				cardId,
				cardProductId,
				cardCompany,
				cardName,
				imageUrl,
				maskedNumber,
				isDefault,
				performanceAmount,
				performanceAmount,
				performanceAmount,
				benefits
			);
		}

		public Long benefitEligibilityPerformanceAmount() {
			return previousMonthPerformanceAmount == null ? performanceAmount : previousMonthPerformanceAmount;
		}

		public Long performanceTargetBaseAmount() {
			return currentMonthPerformanceAmount;
		}
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
		CardBenefitUsageResponse usage,
		List<CardBenefitTierResponse> tiers
	) {
	}

	public record CardBenefitUsageResponse(
		Long dailyAmount,
		Long dailyCount,
		Long monthlyAmount,
		Long monthlyCount,
		Long yearlyAmount,
		Long yearlyCount
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
