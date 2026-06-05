package com.erumpay.recommendation.client;

import com.erumpay.recommendation.dto.PaymentUsageSummaryRequest;
import com.erumpay.recommendation.dto.PaymentUsageSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payment-service", url = "${payment.base-url}")
public interface PaymentServiceClient {

	@PostMapping("/internal/v1/payments/users/{userId}/recommendation-usage-summary")
	PaymentUsageSummaryResponse getRecommendationUsageSummary(
		@PathVariable("userId") Long userId,
		@Valid @RequestBody PaymentUsageSummaryRequest request
	);
}
