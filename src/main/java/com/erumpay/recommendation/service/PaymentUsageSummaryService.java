package com.erumpay.recommendation.service;

import com.erumpay.recommendation.client.PaymentServiceClient;
import com.erumpay.recommendation.dto.PaymentUsageSummaryRequest;
import com.erumpay.recommendation.dto.PaymentUsageSummaryResponse;
import feign.FeignException;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentUsageSummaryService {

	private final PaymentServiceClient paymentServiceClient;
	private final Clock clock;

	public Optional<PaymentUsageSummaryResponse> getPreviousMonthSummary(Long userId) {
		YearMonth previousMonth = YearMonth.now(clock).minusMonths(1);
		PaymentUsageSummaryRequest request = new PaymentUsageSummaryRequest(
			previousMonth.atDay(1),
			previousMonth.atEndOfMonth()
		);

		try {
			PaymentUsageSummaryResponse response = paymentServiceClient.getRecommendationUsageSummary(userId, request);
			if (isEmptyUsage(response)) {
				return Optional.empty();
			}
			return Optional.of(response);
		} catch (FeignException exception) {
			log.warn("payment usage-summary unavailable. userId={}, status={}", userId, exception.status());
			return Optional.empty();
		} catch (RuntimeException exception) {
			log.warn("payment usage-summary failed. userId={}, errorClass={}",
				userId, exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private boolean isEmptyUsage(PaymentUsageSummaryResponse response) {
		if (response == null) {
			return true;
		}
		return nullToZero(response.totalAmount()) <= 0
			&& nullToZero(response.paymentCount()) <= 0
			&& empty(response.merchantUsages())
			&& empty(response.cardUsages());
	}

	private boolean empty(List<?> values) {
		return values == null || values.isEmpty();
	}

	private long nullToZero(Long value) {
		return value == null ? 0L : value;
	}
}
