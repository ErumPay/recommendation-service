package com.erumpay.recommendation.dto;

import java.util.List;

public record AiBestSelectionContext(
	Long paymentId,
	CurrentPayment currentPayment,
	UsageSummary usageSummary,
	List<Strategy> strategies
) {

	public record CurrentPayment(
		String merchantName,
		String normalizedMerchantName,
		String mccCode,
		String serviceCategory,
		String matchedBy,
		String brandCandidate,
		Long amount
	) {
	}

	public record UsageSummary(
		Boolean hasPaymentUsage,
		Long totalAmount,
		Long paymentCount,
		List<MerchantContext> merchants,
		List<CategoryContext> categories,
		List<BrandContext> brands,
		List<CardUsageContext> cards
	) {
	}

	public record MerchantContext(
		String merchantName,
		String normalizedMerchantName,
		String serviceCategory,
		String brandCandidate,
		Long paidAmount,
		Long paymentCount
	) {
	}

	public record CategoryContext(
		String serviceCategory,
		Long paidAmount,
		Long paymentCount,
		Long merchantCount
	) {
	}

	public record BrandContext(
		String brandName,
		String serviceCategory,
		Long paidAmount,
		Long paymentCount
	) {
	}

	public record CardUsageContext(
		Long cardId,
		Long paidAmount,
		Long paymentCount
	) {
	}

	public record Strategy(
		String strategyType,
		Long totalBenefitAmount,
		Long immediateBenefitAmount,
		List<Card> cards,
		List<String> warnings
	) {
	}

	public record Card(
		Long cardId,
		String cardName,
		String cardCompany,
		Long amount,
		Long totalBenefitAmount,
		AppliedBenefitResponse appliedBenefit,
		Long currentPerformanceAmount,
		Long targetPerformanceAmount,
		Long remainingToTarget,
		Long expectedPerformanceAmount,
		Boolean willReachTarget,
		List<String> warnings,
		List<FutureBenefitContext> futureBenefits
	) {
	}

	public record FutureBenefitContext(
		String serviceCategory,
		Boolean brandLimited,
		List<String> brandNames,
		String benefitType,
		String rate,
		Long flatAmount,
		Long maxBenefitPerUse,
		Long monthlyLimitAmount
	) {
	}
}
