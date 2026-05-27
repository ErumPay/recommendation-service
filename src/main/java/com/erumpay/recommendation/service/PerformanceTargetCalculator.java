package com.erumpay.recommendation.service;

import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PerformanceTargetCalculator {

	// [be] 이준혁 260526 1605 | 카드 1장의 다음 목표 실적과 이번 결제 후 달성 여부를 계산한다.
	public PerformanceTargetScore calculate(CardRecommendationSourceCardResponse card, long amount) {
		long currentPerformanceAmount = defaultLong(card.performanceAmount());
		Long targetPerformanceAmount = findNextTargetPerformanceAmount(card, currentPerformanceAmount);
		Long remainingToTarget = targetPerformanceAmount == null
			? null
			: targetPerformanceAmount - currentPerformanceAmount;
		long expectedPerformanceAmount = currentPerformanceAmount + amount;
		boolean willReachTarget = targetPerformanceAmount != null
			&& expectedPerformanceAmount >= targetPerformanceAmount;

		return new PerformanceTargetScore(
			currentPerformanceAmount,
			targetPerformanceAmount,
			remainingToTarget,
			expectedPerformanceAmount,
			willReachTarget
		);
	}

	// [be] 이준혁 260526 1605 | 여러 혜택에 반복된 같은 실적 구간은 카드별 목표 금액 1개로 중복 제거한다.
	private Long findNextTargetPerformanceAmount(
		CardRecommendationSourceCardResponse card,
		long currentPerformanceAmount
	) {
		return safeList(card.benefits()).stream()
			.flatMap(benefit -> safeList(benefit.tiers()).stream())
			.map(CardBenefitTierResponse::minPrevMonthUsage)
			.filter(Objects::nonNull)
			.distinct()
			.filter(target -> target > currentPerformanceAmount)
			.min(Long::compareTo)
			.orElse(null);
	}

	private long defaultLong(Long value) {
		return value == null ? 0L : value;
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	public record PerformanceTargetScore(
		Long currentPerformanceAmount,
		Long targetPerformanceAmount,
		Long remainingToTarget,
		Long expectedPerformanceAmount,
		Boolean willReachTarget
	) {

		public boolean hasTarget() {
			return targetPerformanceAmount != null;
		}
	}
}
