package com.erumpay.recommendation.dto;

import jakarta.validation.constraints.NotBlank;

public record MerchantCategoryResolveRequest(
	@NotBlank(message = "merchantName is required")
	String merchantName,

	String mccCode
) {
}
