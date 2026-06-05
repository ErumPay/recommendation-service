package com.erumpay.recommendation.dto;

import java.time.LocalDate;
import java.util.List;

public record PaymentUsageSummaryResponse(
	Long userId,
	LocalDate from,
	LocalDate to,
	Long totalAmount,
	Long paymentCount,
	List<MerchantUsage> merchantUsages,
	List<CardUsage> cardUsages
) {

	public record MerchantUsage(
		String merchantName,
		Long paymentCount,
		Long paidAmount
	) {
	}

	public record CardUsage(
		Long cardId,
		Long paymentCount,
		Long paidAmount
	) {
	}
}
