package com.erumpay.recommendation.dto;

import com.erumpay.recommendation.domain.enums.MerchantCategoryMatchedBy;
import com.erumpay.recommendation.domain.enums.ServiceCategory;

public record MerchantCategoryResolveResponse(
	String merchantName,
	String mccCode,
	ServiceCategory serviceCategory,
	MerchantCategoryMatchedBy matchedBy,
	String matchedKeyword
) {
}
