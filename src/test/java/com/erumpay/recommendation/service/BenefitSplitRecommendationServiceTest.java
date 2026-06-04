package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.erumpay.recommendation.domain.enums.MerchantCategoryMatchedBy;
import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.BenefitSplitRecommendationRequest;
import com.erumpay.recommendation.dto.BenefitSplitRecommendationResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitUsageResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveResponse;
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
class BenefitSplitRecommendationServiceTest {

	@Mock
	private MerchantCategoryResolverService merchantCategoryResolverService;

	@Mock
	private CardRecommendationSourceService cardRecommendationSourceService;

	private BenefitSplitRecommendationService recommendationService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(
			Instant.parse("2026-05-26T01:00:00Z"),
			ZoneId.of("Asia/Seoul")
		);
		recommendationService = new BenefitSplitRecommendationService(
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

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(10_000L));

		assertThat(response.strategyType()).isEqualTo("BENEFIT_SPLIT");
		assertThat(response.totalBenefitAmount()).isZero();
		assertThat(response.cards()).isEmpty();
		assertThat(response.reason()).isEqualTo("NO_PAYABLE_CARD");
	}

	@Test
	void recommendRejectsInvalidRequestBeforeCalculation() {
		assertThatThrownBy(() -> recommendationService.recommend(
			new BenefitSplitRecommendationRequest(null, "스타벅스 강남점", "5814", 10_000L)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("userId is required");
	}

	@Test
	void recommendReturnsSplitWhenSplitBenefitIsGreaterThanSingle() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", 100_000L, tier(null, 10_000L))
				)),
				card(2L, false, 0L, List.of(
					benefit(200L, "CAFE", "CASHBACK", 10_000L, tier(null, 1_000L))
				))
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(110_000L));

		assertThat(response.totalBenefitAmount()).isEqualTo(11_000L);
		assertThat(response.cards()).hasSize(2);
		assertThat(response.cards().get(0).cardId()).isEqualTo(1L);
		assertThat(response.cards().get(0).amount()).isEqualTo(100_000L);
		assertThat(response.cards().get(0).discountAmount()).isEqualTo(10_000L);
		assertThat(response.cards().get(0).appliedBenefit().benefitId()).isEqualTo(100L);
		assertThat(response.cards().get(0).appliedBenefit().tierId()).isEqualTo(1L);
		assertThat(response.cards().get(0).appliedBenefit().benefitAmount()).isEqualTo(10_000L);
		assertThat(response.cards().get(1).cardId()).isEqualTo(2L);
		assertThat(response.cards().get(1).amount()).isEqualTo(10_000L);
		assertThat(response.cards().get(1).cashbackAmount()).isEqualTo(1_000L);
		assertThat(response.cards().get(1).appliedBenefit().benefitId()).isEqualTo(200L);
		assertThat(response.cards().get(1).appliedBenefit().tierId()).isEqualTo(1L);
		assertThat(response.cards().get(1).appliedBenefit().benefitAmount()).isEqualTo(1_000L);
	}

	@Test
	void recommendReturnsSingleCandidateWhenSplitBenefitIsNotGreater() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", 100_000L, tier(null, 10_000L))
				)),
				card(2L, false, 0L, List.of(
					benefit(200L, "CAFE", "CASHBACK", 10_000L, tier(null, 500L))
				))
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(100_000L));

		assertThat(response.strategyType()).isEqualTo("BENEFIT_SPLIT");
		assertThat(response.totalBenefitAmount()).isEqualTo(10_000L);
		assertThat(response.cards()).hasSize(1);
		assertThat(response.cards().getFirst().cardId()).isEqualTo(1L);
		assertThat(response.cards().getFirst().amount()).isEqualTo(100_000L);
	}

	@Test
	void recommendSkipsAlreadySelectedCardDuringGreedySplit() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", 10_000L, tier(null, 2_000L)),
					benefit(101L, "CAFE", "CASHBACK", null, tier(new BigDecimal("10.00"), null))
				)),
				card(2L, false, 0L, List.of(
					benefit(200L, "CAFE", "MILEAGE", null, tier(new BigDecimal("5.00"), null))
				))
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(20_000L));

		assertThat(response.totalBenefitAmount()).isEqualTo(2_500L);
		assertThat(response.cards()).extracting("cardId").containsExactly(1L, 2L);
		assertThat(response.cards().get(0).amount()).isEqualTo(10_000L);
		assertThat(response.cards().get(1).amount()).isEqualTo(10_000L);
		assertThat(response.cards().get(1).mileageAmount()).isEqualTo(500L);
	}

	@Test
	void recommendUsesBenefitAmountTieBreakWhenSplitEfficiencyTies() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", 10_000L, tier(null, 1_000L))
				)),
				card(2L, false, 0L, List.of(
					benefit(200L, "CAFE", "CASHBACK", 20_000L, tier(null, 2_000L))
				))
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(30_000L));

		assertThat(response.totalBenefitAmount()).isEqualTo(3_000L);
		assertThat(response.cards()).extracting("cardId").containsExactly(2L, 1L);
		assertThat(response.cards().getFirst().amount()).isEqualTo(20_000L);
		assertThat(response.cards().getFirst().cashbackAmount()).isEqualTo(2_000L);
	}

	@Test
	void recommendAssignsRemainderToPerformanceTargetCard() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", 10_000L, tier(null, 2_000L))
				)),
				card(2L, false, 20_000L, List.of(
					benefit(200L, "CAFE", "DISCOUNT", null, tier(30_000L, null, null, null))
				)),
				card(3L, false, 0L, List.of(
					benefit(300L, "CAFE", "CASHBACK", 10_000L, tier(null, 1_500L))
				))
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(30_000L));

		assertThat(response.totalBenefitAmount()).isEqualTo(3_500L);
		assertThat(response.cards()).hasSize(3);
		assertThat(response.cards()).extracting("cardId").containsExactly(1L, 3L, 2L);
		assertThat(response.cards().get(2).amount()).isEqualTo(10_000L);
		assertThat(response.cards().get(2).totalBenefitAmount()).isZero();
		assertThat(response.cards().get(2).appliedBenefit()).isNull();
		assertThat(response.cards().get(2).targetPerformanceAmount()).isEqualTo(30_000L);
		assertThat(response.cards().get(2).willReachTarget()).isTrue();
	}

	@Test
	void recommendAssignsRemainderToPerformanceTargetCardEvenWhenTargetCannotBeReached() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", 10_000L, tier(null, 2_000L))
				)),
				card(2L, false, 20_000L, List.of(
					benefit(200L, "ETC", "DISCOUNT", null, tier(100_000L, null, null, null))
				)),
				card(3L, false, 0L, List.of(
					benefit(300L, "CAFE", "CASHBACK", 10_000L, tier(null, 1_500L))
				))
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(25_000L));

		assertThat(response.totalBenefitAmount()).isEqualTo(3_500L);
		assertThat(response.cards()).extracting("cardId").containsExactly(1L, 3L, 2L);
		assertThat(response.cards().get(2).amount()).isEqualTo(5_000L);
		assertThat(response.cards().get(2).targetPerformanceAmount()).isEqualTo(100_000L);
		assertThat(response.cards().get(2).expectedPerformanceAmount()).isEqualTo(25_000L);
		assertThat(response.cards().get(2).willReachTarget()).isFalse();
	}

	@Test
	void recommendUsesExistingSplitAmountWhenFindingRemainderPerformanceTargetCard() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 20_000L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", 20_000L, tier(null, 1_000L)),
					benefit(101L, "ETC", "DISCOUNT", null, tier(50_000L, null, null, 500L))
				)),
				card(2L, false, 0L, List.of(
					benefit(200L, "CAFE", "CASHBACK", 10_000L, tier(null, 2_000L))
				))
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(40_000L));

		assertThat(response.totalBenefitAmount()).isEqualTo(3_000L);
		assertThat(response.cards()).extracting("cardId").containsExactly(1L, 2L);
		assertThat(response.cards().getFirst().amount()).isEqualTo(30_000L);
		assertThat(response.cards().getFirst().discountAmount()).isEqualTo(1_000L);
		assertThat(response.cards().getFirst().targetPerformanceAmount()).isEqualTo(50_000L);
		assertThat(response.cards().getFirst().willReachTarget()).isTrue();
	}

	@Test
	void recommendAssignsRemainderToTopSplitCardWhenNoPerformanceTargetExists() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", 10_000L, tier(null, 2_000L))
				)),
				card(2L, false, 0L, List.of(
					benefit(200L, "CAFE", "CASHBACK", 10_000L, tier(null, 1_500L))
				))
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(25_000L));

		assertThat(response.totalBenefitAmount()).isEqualTo(3_500L);
		assertThat(response.cards()).extracting("cardId").containsExactly(1L, 2L);
		assertThat(response.cards().getFirst().amount()).isEqualTo(15_000L);
		assertThat(response.cards().getFirst().discountAmount()).isEqualTo(2_000L);
		assertThat(response.cards().getFirst().warnings()).isEmpty();
	}

	@Test
	void recommendFallsBackToDefaultCardWhenNoBenefitCanBeApplied() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, true, 0L, List.of())
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(10_000L));

		assertThat(response.totalBenefitAmount()).isZero();
		assertThat(response.cards()).hasSize(1);
		assertThat(response.cards().getFirst().cardId()).isEqualTo(1L);
		assertThat(response.cards().getFirst().warnings()).containsExactly("NO_APPLICABLE_BENEFIT");
	}

	@Test
	void recommendReturnsDefaultMissingWhenFallbackNeedsDefaultCardButDefaultIsMissing() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of())
			)));

		BenefitSplitRecommendationResponse response = recommendationService.recommend(request(10_000L));

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

	private BenefitSplitRecommendationRequest request(long amount) {
		return new BenefitSplitRecommendationRequest(10L, "스타벅스 강남점", "5814", amount);
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
		Long minAmount,
		CardBenefitTierResponse tier
	) {
		return new CardBenefitResponse(
			benefitId,
			serviceCategory,
			benefitType,
			minAmount,
			null,
			null,
			"ALL",
			"테스트 혜택",
			List.of(),
			emptyUsage(),
			List.of(tier)
		);
	}

	private CardBenefitTierResponse tier(BigDecimal rate, Long flatAmount) {
		return tier(0L, null, rate, flatAmount);
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
