package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.service.PerformanceTargetCalculator.PerformanceTargetScore;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerformanceTargetCalculatorTest {

	private final PerformanceTargetCalculator calculator = new PerformanceTargetCalculator();

	@Test
	void calculateUsesClosestDistinctMinPrevMonthUsageAboveCurrentPerformance() {
		CardRecommendationSourceCardResponse card = card(20_000L, List.of(
			benefit(List.of(tier(30_000L), tier(30_000L), tier(50_000L))),
			benefit(List.of(tier(30_000L), tier(100_000L)))
		));

		PerformanceTargetScore score = calculator.calculate(card, 15_000L);

		assertThat(score.currentPerformanceAmount()).isEqualTo(20_000L);
		assertThat(score.targetPerformanceAmount()).isEqualTo(30_000L);
		assertThat(score.remainingToTarget()).isEqualTo(10_000L);
		assertThat(score.expectedPerformanceAmount()).isEqualTo(35_000L);
		assertThat(score.willReachTarget()).isTrue();
	}

	@Test
	void calculateReturnsNoTargetWhenAllTiersAreAlreadyReached() {
		CardRecommendationSourceCardResponse card = card(100_000L, List.of(
			benefit(List.of(tier(30_000L), tier(100_000L)))
		));

		PerformanceTargetScore score = calculator.calculate(card, 15_000L);

		assertThat(score.targetPerformanceAmount()).isNull();
		assertThat(score.remainingToTarget()).isNull();
		assertThat(score.expectedPerformanceAmount()).isEqualTo(115_000L);
		assertThat(score.willReachTarget()).isFalse();
		assertThat(score.hasTarget()).isFalse();
	}

	@Test
	void calculateUsesCurrentMonthPerformanceWhenSeparatedFromPreviousMonthPerformance() {
		CardRecommendationSourceCardResponse card = card(
			100_000L,
			20_000L,
			List.of(benefit(List.of(tier(30_000L), tier(100_000L))))
		);

		PerformanceTargetScore score = calculator.calculate(card, 15_000L);

		assertThat(score.currentPerformanceAmount()).isEqualTo(20_000L);
		assertThat(score.targetPerformanceAmount()).isEqualTo(30_000L);
		assertThat(score.remainingToTarget()).isEqualTo(10_000L);
		assertThat(score.expectedPerformanceAmount()).isEqualTo(35_000L);
		assertThat(score.willReachTarget()).isTrue();
	}

	@Test
	void calculateDoesNotUsePreviousMonthPerformanceWhenCurrentMonthPerformanceIsMissing() {
		CardRecommendationSourceCardResponse card = card(
			420_000L,
			null,
			List.of(benefit(List.of(tier(400_000L))))
		);

		PerformanceTargetScore score = calculator.calculate(card, 1_000_000L);

		assertThat(score.currentPerformanceAmount()).isZero();
		assertThat(score.targetPerformanceAmount()).isEqualTo(400_000L);
		assertThat(score.remainingToTarget()).isEqualTo(400_000L);
		assertThat(score.expectedPerformanceAmount()).isEqualTo(1_000_000L);
		assertThat(score.willReachTarget()).isTrue();
	}

	private CardRecommendationSourceCardResponse card(
		Long performanceAmount,
		List<CardBenefitResponse> benefits
	) {
		return card(performanceAmount, performanceAmount, benefits);
	}

	private CardRecommendationSourceCardResponse card(
		Long previousMonthPerformanceAmount,
		Long currentMonthPerformanceAmount,
		List<CardBenefitResponse> benefits
	) {
		return new CardRecommendationSourceCardResponse(
			1L,
			100L,
			"테스트카드사",
			"테스트카드",
			"https://example.com/card.png",
			"1234-****-****-0001",
			true,
			previousMonthPerformanceAmount,
			previousMonthPerformanceAmount,
			currentMonthPerformanceAmount,
			benefits
		);
	}

	private CardBenefitResponse benefit(List<CardBenefitTierResponse> tiers) {
		return new CardBenefitResponse(
			1L,
			"CAFE",
			"DISCOUNT",
			null,
			null,
			null,
			"ALL",
			"테스트 혜택",
			List.of(),
			null,
			tiers
		);
	}

	private CardBenefitTierResponse tier(Long minPrevMonthUsage) {
		return new CardBenefitTierResponse(
			1L,
			minPrevMonthUsage,
			null,
			null,
			1_000L,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}
}
