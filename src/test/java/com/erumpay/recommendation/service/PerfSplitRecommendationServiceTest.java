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
import com.erumpay.recommendation.dto.PerfSplitRecommendationRequest;
import com.erumpay.recommendation.dto.PerfSplitRecommendationResponse;
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
class PerfSplitRecommendationServiceTest {

	@Mock
	private MerchantCategoryResolverService merchantCategoryResolverService;

	@Mock
	private CardRecommendationSourceService cardRecommendationSourceService;

	private PerfSplitRecommendationService recommendationService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(
			Instant.parse("2026-05-26T01:00:00Z"),
			ZoneId.of("Asia/Seoul")
		);
		recommendationService = new PerfSplitRecommendationService(
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

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(10_000L));

		assertThat(response.strategyType()).isEqualTo("PERF_SPLIT");
		assertThat(response.totalBenefitAmount()).isZero();
		assertThat(response.cards()).isEmpty();
		assertThat(response.reason()).isEqualTo("NO_PAYABLE_CARD");
	}

	@Test
	void recommendReturnsNoPayableCardWhenCardSourceIsNull() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(null);

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(10_000L));

		assertThat(response.strategyType()).isEqualTo("PERF_SPLIT");
		assertThat(response.totalBenefitAmount()).isZero();
		assertThat(response.cards()).isEmpty();
		assertThat(response.reason()).isEqualTo("NO_PAYABLE_CARD");
	}

	@Test
	void recommendRejectsInvalidRequestBeforeCalculation() {
		assertThatThrownBy(() -> recommendationService.recommend(
			new PerfSplitRecommendationRequest(null, "Starbucks Gangnam", "5814", 10_000L)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("userId is required");
	}

	@Test
	void recommendReturnsSplitWhenSplitReachesMoreTargetsThanSingle() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					targetBenefit(101L, 30_000L),
					flatBenefit(100L, 500L)
				)),
				card(2L, false, 90_000L, List.of(
					targetBenefit(201L, 100_000L),
					flatBenefit(200L, 700L)
				))
			)));

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(20_000L));

		assertThat(response.totalBenefitAmount()).isEqualTo(1_200L);
		assertThat(response.cards()).extracting("cardId").containsExactly(2L, 1L);
		assertThat(response.cards()).extracting("amount").containsExactly(10_000L, 10_000L);
		assertThat(response.cards().get(0).appliedBenefit().benefitId()).isEqualTo(200L);
		assertThat(response.cards().get(0).appliedBenefit().benefitAmount()).isEqualTo(700L);
		assertThat(response.cards().get(1).appliedBenefit().benefitId()).isEqualTo(100L);
		assertThat(response.cards().get(1).appliedBenefit().benefitAmount()).isEqualTo(500L);
		assertThat(response.cards()).extracting("willReachTarget").containsExactly(true, true);
	}

	@Test
	void recommendReturnsSingleCandidateWhenSplitIsNotBetter() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					targetBenefit(101L, 30_000L),
					flatBenefit(100L, 500L)
				))
			)));

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(10_000L));

		assertThat(response.strategyType()).isEqualTo("PERF_SPLIT");
		assertThat(response.cards()).hasSize(1);
		assertThat(response.cards().getFirst().cardId()).isEqualTo(1L);
		assertThat(response.cards().getFirst().amount()).isEqualTo(10_000L);
		assertThat(response.cards().getFirst().willReachTarget()).isTrue();
	}

	@Test
	void recommendSplitsByCurrentMonthPerformanceEvenWhenPreviousMonthPerformanceAlreadyReachedTargets() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 420_000L, null, List.of(targetBenefit(101L, 400_000L))),
				card(2L, false, 475_000L, null, List.of(targetBenefit(201L, 500_000L)))
			)));

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(1_000_000L));

		assertThat(response.cards()).extracting("cardId").containsExactly(2L, 1L);
		assertThat(response.cards()).extracting("amount").containsExactly(500_000L, 500_000L);
		assertThat(response.cards()).extracting("currentPerformanceAmount").containsExactly(0L, 0L);
		assertThat(response.cards()).extracting("targetPerformanceAmount").containsExactly(500_000L, 400_000L);
		assertThat(response.cards()).extracting("willReachTarget").containsExactly(true, true);
	}

	@Test
	void recommendAssignsRemainderToFirstPerformanceCardWhenNoTargetRemains() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					targetBenefit(101L, 30_000L),
					flatBenefit(100L, 500L)
				)),
				card(2L, false, 90_000L, List.of(
					targetBenefit(201L, 100_000L),
					flatBenefit(200L, 700L)
				))
			)));

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(25_000L));

		assertThat(response.totalBenefitAmount()).isEqualTo(1_200L);
		assertThat(response.cards()).extracting("cardId").containsExactly(2L, 1L);
		assertThat(response.cards()).extracting("amount").containsExactly(15_000L, 10_000L);
		assertThat(response.cards().getFirst().expectedPerformanceAmount()).isEqualTo(105_000L);
		assertThat(response.cards().getFirst().willReachTarget()).isTrue();
	}

	@Test
	void recommendAssignsInsufficientAmountToNearestTargetCard() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					targetBenefit(101L, 30_000L),
					flatBenefit(100L, 500L)
				)),
				card(2L, false, 30_000L, List.of(
					targetBenefit(201L, 50_000L),
					flatBenefit(200L, 700L)
				))
			)));

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(5_000L));

		assertThat(response.cards()).hasSize(1);
		assertThat(response.cards().getFirst().cardId()).isEqualTo(1L);
		assertThat(response.cards().getFirst().amount()).isEqualTo(5_000L);
		assertThat(response.cards().getFirst().targetPerformanceAmount()).isEqualTo(30_000L);
		assertThat(response.cards().getFirst().willReachTarget()).isFalse();
	}

	@Test
	void recommendUsesBenefitScoreWhenAllocationPriorityTies() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					targetBenefit(101L, 30_000L),
					flatBenefit(100L, 500L)
				)),
				card(2L, false, 20_000L, List.of(
					targetBenefit(201L, 30_000L),
					flatBenefit(200L, 900L)
				))
			)));

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(20_000L));

		assertThat(response.cards()).extracting("cardId").containsExactly(2L, 1L);
		assertThat(response.cards().getFirst().totalBenefitAmount()).isEqualTo(900L);
	}

	@Test
	void recommendFallsBackToDefaultCardWhenNoPerformanceTargetExists() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 100_000L, List.of(
					flatBenefit(100L, 500L)
				)),
				card(2L, true, 100_000L, List.of(
					flatBenefit(200L, 700L)
				))
			)));

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(10_000L));

		assertThat(response.cards()).hasSize(1);
		assertThat(response.cards().getFirst().cardId()).isEqualTo(2L);
		assertThat(response.cards().getFirst().targetPerformanceAmount()).isNull();
		assertThat(response.cards().getFirst().warnings()).containsExactly("NO_PERFORMANCE_TARGET");
	}

	@Test
	void recommendReturnsDefaultMissingWhenFallbackNeedsDefaultCardButDefaultIsMissing() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 100_000L, List.of(
					flatBenefit(100L, 500L)
				))
			)));

		PerfSplitRecommendationResponse response = recommendationService.recommend(request(10_000L));

		assertThat(response.cards()).isEmpty();
		assertThat(response.reason()).isEqualTo("CARD_DEFAULT_MISSING");
	}

	private void givenCategory(ServiceCategory serviceCategory) {
		when(merchantCategoryResolverService.resolve(any()))
			.thenReturn(new MerchantCategoryResolveResponse(
				"Starbucks Gangnam",
				"5814",
				serviceCategory,
				MerchantCategoryMatchedBy.MCC,
				null
			));
	}

	private PerfSplitRecommendationRequest request(long amount) {
		return new PerfSplitRecommendationRequest(10L, "Starbucks Gangnam", "5814", amount);
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
		return card(cardId, isDefault, performanceAmount, performanceAmount, benefits);
	}

	private CardRecommendationSourceCardResponse card(
		Long cardId,
		boolean isDefault,
		Long previousMonthPerformanceAmount,
		Long currentMonthPerformanceAmount,
		List<CardBenefitResponse> benefits
	) {
		return new CardRecommendationSourceCardResponse(
			cardId,
			cardId * 100L,
			"Test Card Company",
			"Test Card " + cardId,
			"https://example.com/card.png",
			"1234-****-****-" + cardId,
			isDefault,
			previousMonthPerformanceAmount,
			previousMonthPerformanceAmount,
			currentMonthPerformanceAmount,
			benefits
		);
	}

	private CardBenefitResponse flatBenefit(Long benefitId, Long flatAmount) {
		return benefit(benefitId, "CAFE", "DISCOUNT", List.of(tier(0L, null, null, flatAmount)));
	}

	private CardBenefitResponse targetBenefit(Long benefitId, Long targetPerformanceAmount) {
		return benefit(benefitId, "ETC", "DISCOUNT", List.of(tier(targetPerformanceAmount, null, null, null)));
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
			"Test Benefit",
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
