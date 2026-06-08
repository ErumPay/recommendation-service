package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.erumpay.recommendation.domain.enums.MerchantCategoryMatchedBy;
import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitUsageResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveResponse;
import com.erumpay.recommendation.dto.PerfSingleRecommendationRequest;
import com.erumpay.recommendation.dto.PerfSingleRecommendationResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerfSingleRecommendationServiceTest {

	@Mock
	private MerchantCategoryResolverService merchantCategoryResolverService;

	@Mock
	private CardRecommendationSourceService cardRecommendationSourceService;

	private PerfSingleRecommendationService recommendationService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(
			Instant.parse("2026-05-26T01:00:00Z"),
			ZoneId.of("Asia/Seoul")
		);
		recommendationService = new PerfSingleRecommendationService(
			merchantCategoryResolverService,
			cardRecommendationSourceService,
			new BenefitScoreCalculator(),
			new PerformanceTargetCalculator(),
			clock
		);
	}

	@Test
	void recommendReturnsNoPayableCardWhenCardSourceIsEmpty() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of()));

		PerfSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.strategyType()).isEqualTo("PERF_SINGLE");
		assertThat(response.cards()).isEmpty();
		assertThat(response.reason()).isEqualTo("NO_PAYABLE_CARD");
	}

	@Test
	void recommendRejectsInvalidRequestBeforeCalculation() {
		assertThatThrownBy(() -> recommendationService.recommend(
			new PerfSingleRecommendationRequest(null, "스타벅스 강남점", "5814", 10_000L)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("userId is required");
	}

	@Test
	void recommendSelectsCardWithSmallestRemainingToTarget() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", tier(50_000L, null, null, 1_000L))
				)),
				card(2L, false, 90_000L, List.of(
					benefit(200L, "CAFE", "DISCOUNT", tier(100_000L, null, null, 1_000L))
				))
			)));

		PerfSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().cardId()).isEqualTo(2L);
		assertThat(response.cards().getFirst().remainingToTarget()).isEqualTo(10_000L);
		assertThat(response.cards().getFirst().willReachTarget()).isTrue();
	}

	@Test
	void recommendUsesHigherTargetWhenRemainingToTargetIsTie() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", tier(30_000L, null, null, 1_000L))
				)),
				card(2L, false, 90_000L, List.of(
					benefit(200L, "CAFE", "DISCOUNT", tier(100_000L, null, null, 1_000L))
				))
			)));

		PerfSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().cardId()).isEqualTo(2L);
		assertThat(response.cards().getFirst().targetPerformanceAmount()).isEqualTo(100_000L);
	}

	@Test
	void recommendUsesBenefitScoreWhenPerformanceScoreIsTie() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", List.of(
						tier(0L, null, null, 1_000L),
						tier(30_000L, null, null, 1_000L)
					))
				)),
				card(2L, false, 20_000L, List.of(
					benefit(200L, "CAFE", "DISCOUNT", List.of(
						tier(0L, null, null, 2_000L),
						tier(30_000L, null, null, 2_000L)
					))
				))
			)));

		PerfSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().cardId()).isEqualTo(2L);
		assertThat(response.cards().getFirst().totalBenefitAmount()).isEqualTo(2_000L);
		assertThat(response.cards().getFirst().appliedBenefit().benefitId()).isEqualTo(200L);
		assertThat(response.cards().getFirst().appliedBenefit().tierId()).isEqualTo(1L);
		assertThat(response.cards().getFirst().appliedBenefit().benefitAmount()).isEqualTo(2_000L);
		assertThat(response.totalBenefitAmount()).isEqualTo(2_000L);
	}

	@Test
	void recommendUsesDefaultCardWhenPerformanceAndBenefitScoresAreTie() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", List.of(
						tier(0L, null, null, 1_000L),
						tier(30_000L, null, null, 1_000L)
					))
				)),
				card(2L, true, 20_000L, List.of(
					benefit(200L, "CAFE", "DISCOUNT", List.of(
						tier(0L, null, null, 1_000L),
						tier(30_000L, null, null, 1_000L)
					))
				))
			)));

		PerfSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().cardId()).isEqualTo(2L);
	}

	@Test
	void recommendUsesCardIdWhenAllPriorityConditionsAreTie() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(2L, false, 20_000L, List.of(
					benefit(200L, "CAFE", "DISCOUNT", List.of(
						tier(0L, null, null, 1_000L),
						tier(30_000L, null, null, 1_000L)
					))
				)),
				card(1L, false, 20_000L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", List.of(
						tier(0L, null, null, 1_000L),
						tier(30_000L, null, null, 1_000L)
					))
				))
			)));

		PerfSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().cardId()).isEqualTo(1L);
	}

	@Test
	void recommendFallsBackToDefaultCardWhenNoPerformanceTargetExists() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 100_000L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", tier(30_000L, null, null, 1_000L))
				)),
				card(2L, true, 100_000L, List.of(
					benefit(200L, "CAFE", "DISCOUNT", tier(30_000L, null, null, 2_000L))
				))
			)));

		PerfSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().cardId()).isEqualTo(2L);
		assertThat(response.cards().getFirst().targetPerformanceAmount()).isNull();
		assertThat(response.cards().getFirst().expectedPerformanceAmount()).isEqualTo(110_000L);
		assertThat(response.cards().getFirst().warnings()).containsExactly("NO_PERFORMANCE_TARGET");
	}

	@Test
	void recommendReturnsDefaultMissingWhenFallbackNeedsDefaultCardButDefaultIsMissing() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 100_000L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", tier(30_000L, null, null, 1_000L))
				))
			)));

		PerfSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards()).isEmpty();
		assertThat(response.reason()).isEqualTo("CARD_DEFAULT_MISSING");
	}

	private void givenCategory(ServiceCategory serviceCategory) {
		when(merchantCategoryResolverService.resolve(any()))
			.thenReturn(new MerchantCategoryResolveResponse(
				"스타벅스 강남점",
				"5814",
				serviceCategory,
				MerchantCategoryMatchedBy.MCC,
				null
			));
	}

	private PerfSingleRecommendationRequest request() {
		return new PerfSingleRecommendationRequest(10L, "스타벅스 강남점", "5814", 10_000L);
	}

	private CardRecommendationSourceResponse source(List<CardRecommendationSourceCardResponse> cards) {
		return new CardRecommendationSourceResponse(10L, "202604", cards);
	}

	private CardRecommendationSourceCardResponse card(
		Long cardId,
		boolean isDefault,
		Long performanceAmount,
		List<CardBenefitResponse> benefits
	) {
		return new CardRecommendationSourceCardResponse(
			cardId,
			cardId * 100L,
			"테스트카드사",
			"테스트카드" + cardId,
			"https://example.com/card.png",
			"1234-****-****-" + cardId,
			isDefault,
			performanceAmount,
			benefits
		);
	}

	private CardBenefitResponse benefit(
		Long benefitId,
		String serviceCategory,
		String benefitType,
		CardBenefitTierResponse tier
	) {
		return benefit(benefitId, serviceCategory, benefitType, List.of(tier));
	}

	private CardBenefitResponse benefit(
		Long benefitId,
		String serviceCategory,
		String benefitType,
		List<CardBenefitTierResponse> tiers
	) {
		return new CardBenefitResponse(
			benefitId,
			serviceCategory,
			benefitType,
			null,
			null,
			null,
			"ALL",
			"테스트 혜택",
			List.of(),
			emptyUsage(),
			tiers
		);
	}

	private CardBenefitTierResponse tier(
		Long minPrevMonthUsage,
		Long maxPrevMonthUsage,
		BigDecimal rate,
		Long flatAmount
	) {
		return new CardBenefitTierResponse(
			1L,
			minPrevMonthUsage,
			maxPrevMonthUsage,
			rate,
			flatAmount,
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

	private CardBenefitUsageResponse emptyUsage() {
		return new CardBenefitUsageResponse(0L, 0L, 0L, 0L, 0L, 0L);
	}
}
