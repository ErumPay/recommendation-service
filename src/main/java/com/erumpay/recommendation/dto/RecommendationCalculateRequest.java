package com.erumpay.recommendation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RecommendationCalculateRequest(
	@NotNull(message = "paymentId is required")
	@Positive(message = "paymentId must be positive")
	Long paymentId,

	@NotNull(message = "userId is required")
	@Positive(message = "userId must be positive")
	Long userId,

	@NotBlank(message = "merchantName is required")
	String merchantName,

	@NotBlank(message = "mccCode is required")
	String mccCode,

	@NotNull(message = "amount is required")
	@Positive(message = "amount must be positive")
	Long amount
) {
}
