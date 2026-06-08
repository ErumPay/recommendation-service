package com.erumpay.recommendation.dto;

import java.time.LocalDate;

public record PaymentUsageSummaryRequest(
	LocalDate from,
	LocalDate to
) {
}
