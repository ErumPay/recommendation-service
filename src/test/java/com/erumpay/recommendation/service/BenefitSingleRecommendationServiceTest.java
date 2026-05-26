package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.erumpay.recommendation.domain.enums.MerchantCategoryMatchedBy;
import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationRequest;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationResponse;
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
class BenefitSingleRecommendationServiceTest {

	@Mock
	private MerchantCategoryResolverService merchantCategoryResolverService;

	@Mock
	private CardRecommendationSourceService cardRecommendationSourceService;

	private BenefitSingleRecommendationService recommendationService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(
			Instant.parse("2026-05-26T01:00:00Z"),
			ZoneId.of("Asia/Seoul")
		);
		recommendationService = new BenefitSingleRecommendationService(
			merchantCategoryResolverService,
			cardRecommendationSourceService,
			clock
		);
	}

	@Test
	void recommendReturnsNoPayableCardWhenCardSourceIsEmpty() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of()));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.strategyType()).isEqualTo("BENEFIT_SINGLE");
		assertThat(response.totalBenefitAmount()).isZero();
		assertThat(response.cards()).isEmpty();
		assertThat(response.reason()).isEqualTo("NO_PAYABLE_CARD");
	}

	@Test
	void recommendSelectsCardWithLargestTotalBenefitAmount() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", tier(null, 1_000L))
				)),
				card(2L, false, 0L, List.of(
					benefit(200L, "CAFE", "CASHBACK", tier(new BigDecimal("20.50"), null)),
					benefit(201L, "ALL", "MILEAGE", tier(null, 100L))
				))
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.totalBenefitAmount()).isEqualTo(2_150L);
		assertThat(response.cards()).hasSize(1);
		assertThat(response.cards().getFirst().cardId()).isEqualTo(2L);
		assertThat(response.cards().getFirst().cashbackAmount()).isEqualTo(2_050L);
		assertThat(response.cards().getFirst().mileageAmount()).isEqualTo(100L);
	}

	@Test
	void recommendAppliesBrandTierAndAmountLimits() {
		givenCategory(ServiceCategory.CAFE);
		CardBenefitUsageResponse usage = new CardBenefitUsageResponse(1_000L, 0L, 1_000L, 0L, 1_000L, 0L);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 50_000L, List.of(
					benefit(
						100L,
						"CAFE",
						"DISCOUNT",
						5_000L,
						"09:00",
						"18:00",
						"ALL",
						List.of("스타 벅스"),
						usage,
						List.of(
							tier(30_000L, 50_000L, null, 5_000L, 5_000L, null, 5_000L),
							tier(50_000L, null, null, 5_000L, 3_000L, null, 2_500L)
						)
					)
				))
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().cardId()).isEqualTo(1L);
		assertThat(response.cards().getFirst().discountAmount()).isEqualTo(1_500L);
		assertThat(response.cards().getFirst().totalBenefitAmount()).isEqualTo(1_500L);
	}

	@Test
	void recommendFallsBackToDefaultCardWhenNoBenefitCanBeApplied() {
		givenCategory(ServiceCategory.CAFE);
		CardBenefitUsageResponse usage = new CardBenefitUsageResponse(0L, 1L, 0L, 1L, 0L, 1L);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", tier(null, 1_000L, 1, null), usage)
				)),
				card(2L, true, 0L, List.of())
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.totalBenefitAmount()).isZero();
		assertThat(response.cards()).hasSize(1);
		assertThat(response.cards().getFirst().cardId()).isEqualTo(2L);
		assertThat(response.cards().getFirst().warnings()).containsExactly("NO_APPLICABLE_BENEFIT");
	}

	@Test
	void recommendAllowsOnlyAllCategoryBenefitWhenPaymentCategoryIsEtc() {
		givenCategory(ServiceCategory.ETC);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(100L, "CAFE", "DISCOUNT", tier(null, 5_000L)),
					benefit(101L, "ALL", "CASHBACK", tier(null, 1_000L))
				))
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.totalBenefitAmount()).isEqualTo(1_000L);
		assertThat(response.cards().getFirst().cashbackAmount()).isEqualTo(1_000L);
		assertThat(response.cards().getFirst().discountAmount()).isZero();
	}

	@Test
	void recommendExcludesBenefitWhenOnlyOneTimeBoundaryExists() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, true, 0L, List.of(
					benefit(
						100L,
						"CAFE",
						"DISCOUNT",
						null,
						"09:00",
						null,
						"ALL",
						List.of(),
						emptyUsage(),
						List.of(tier(null, 1_000L))
					)
				))
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.totalBenefitAmount()).isZero();
		assertThat(response.cards().getFirst().cardId()).isEqualTo(1L);
		assertThat(response.cards().getFirst().warnings()).containsExactly("NO_APPLICABLE_BENEFIT");
	}

	@Test
	void recommendAppliesBenefitInOvernightTimeRange() {
		Clock nightClock = Clock.fixed(
			Instant.parse("2026-05-26T16:30:00Z"),
			ZoneId.of("Asia/Seoul")
		);
		recommendationService = new BenefitSingleRecommendationService(
			merchantCategoryResolverService,
			cardRecommendationSourceService,
			nightClock
		);
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(
						100L,
						"CAFE",
						"DISCOUNT",
						null,
						"22:00",
						"02:00",
						"ALL",
						List.of(),
						emptyUsage(),
						List.of(tier(null, 1_000L))
					)
				))
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.totalBenefitAmount()).isEqualTo(1_000L);
		assertThat(response.cards().getFirst().cardId()).isEqualTo(1L);
	}

	@Test
	void recommendAppliesMaxBenefitPerUseBeforeOtherAmountLimits() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(
						100L,
						"CAFE",
						"DISCOUNT",
						null,
						null,
						null,
						"ALL",
						List.of(),
						emptyUsage(),
						List.of(tier(0L, null, new BigDecimal("50.00"), null, 3_000L, null, null))
					)
				))
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.totalBenefitAmount()).isEqualTo(3_000L);
		assertThat(response.cards().getFirst().discountAmount()).isEqualTo(3_000L);
	}

	@Test
	void recommendUsesDefaultCardThenCardIdWhenBenefitAmountsTie() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(3L, false, 0L, List.of(
					benefit(300L, "CAFE", "DISCOUNT", tier(null, 1_000L))
				)),
				card(2L, false, 0L, List.of(
					benefit(200L, "CAFE", "DISCOUNT", tier(null, 1_000L))
				)),
				card(5L, true, 0L, List.of(
					benefit(500L, "CAFE", "DISCOUNT", tier(null, 1_000L))
				))
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().cardId()).isEqualTo(5L);
	}

	@Test
	void recommendUsesCardIdWhenBenefitAmountsAndDefaultFlagTie() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(3L, false, 0L, List.of(
					benefit(300L, "CAFE", "DISCOUNT", tier(null, 1_000L))
				)),
				card(2L, false, 0L, List.of(
					benefit(200L, "CAFE", "DISCOUNT", tier(null, 1_000L))
				))
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().cardId()).isEqualTo(2L);
	}

	@Test
	void recommendAddsBoundaryWarningsToApplicableBenefit() {
		Clock boundaryClock = Clock.fixed(
			Instant.parse("2026-05-29T14:55:00Z"),
			ZoneId.of("Asia/Seoul")
		);
		recommendationService = new BenefitSingleRecommendationService(
			merchantCategoryResolverService,
			cardRecommendationSourceService,
			boundaryClock
		);
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L))
			.thenReturn(source(List.of(
				card(1L, false, 0L, List.of(
					benefit(
						100L,
						"CAFE",
						"DISCOUNT",
						null,
						"00:00",
						"23:59",
						"WEEKDAY",
						List.of(),
						emptyUsage(),
						List.of(tier(null, 1_000L, "[SHARED_LIMIT monthly]"))
					)
				))
			)));

		BenefitSingleRecommendationResponse response = recommendationService.recommend(request());

		assertThat(response.cards().getFirst().warnings())
			.containsExactly(
				"TIME_END_BOUNDARY_NEAR",
				"DAY_BOUNDARY_NEAR",
				"SHARED_LIMIT_NOT_APPLIED"
			);
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

	private BenefitSingleRecommendationRequest request() {
		return new BenefitSingleRecommendationRequest(10L, "스타벅스 강남점", "5814", 10_000L);
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
		CardBenefitTierResponse tier
	) {
		return benefit(
			benefitId,
			serviceCategory,
			benefitType,
			null,
			null,
			null,
			"ALL",
			List.of(),
			emptyUsage(),
			List.of(tier)
		);
	}

	private CardBenefitResponse benefit(
		Long benefitId,
		String serviceCategory,
		String benefitType,
		CardBenefitTierResponse tier,
		CardBenefitUsageResponse usage
	) {
		return benefit(
			benefitId,
			serviceCategory,
			benefitType,
			null,
			null,
			null,
			"ALL",
			List.of(),
			usage,
			List.of(tier)
		);
	}

	private CardBenefitResponse benefit(
		Long benefitId,
		String serviceCategory,
		String benefitType,
		Long minAmount,
		String timeStart,
		String timeEnd,
		String dayCondition,
		List<String> brandNames,
		CardBenefitUsageResponse usage,
		List<CardBenefitTierResponse> tiers
	) {
		return new CardBenefitResponse(
			benefitId,
			serviceCategory,
			benefitType,
			minAmount,
			timeStart,
			timeEnd,
			dayCondition,
			"테스트 혜택",
			brandNames,
			usage,
			tiers
		);
	}

	private CardBenefitTierResponse tier(BigDecimal rate, Long flatAmount) {
		return tier(0L, null, rate, flatAmount, null, null, null);
	}

	private CardBenefitTierResponse tier(BigDecimal rate, Long flatAmount, String tierDesc) {
		return tier(0L, null, rate, flatAmount, null, null, null, tierDesc);
	}

	private CardBenefitTierResponse tier(BigDecimal rate, Long flatAmount, Integer dailyLimitCount, Long dailyLimitAmount) {
		return tier(0L, null, rate, flatAmount, null, dailyLimitCount, dailyLimitAmount);
	}

	private CardBenefitTierResponse tier(
		Long minPrevMonthUsage,
		Long maxPrevMonthUsage,
		BigDecimal rate,
		Long flatAmount,
		Long maxBenefitPerUse,
		Integer dailyLimitCount,
		Long dailyLimitAmount
	) {
		return tier(
			minPrevMonthUsage,
			maxPrevMonthUsage,
			rate,
			flatAmount,
			maxBenefitPerUse,
			dailyLimitCount,
			dailyLimitAmount,
			null
		);
	}

	private CardBenefitTierResponse tier(
		Long minPrevMonthUsage,
		Long maxPrevMonthUsage,
		BigDecimal rate,
		Long flatAmount,
		Long maxBenefitPerUse,
		Integer dailyLimitCount,
		Long dailyLimitAmount,
		String tierDesc
	) {
		return new CardBenefitTierResponse(
			1L,
			minPrevMonthUsage,
			maxPrevMonthUsage,
			rate,
			flatAmount,
			maxBenefitPerUse,
			dailyLimitCount,
			dailyLimitAmount,
			null,
			null,
			null,
			null,
			tierDesc
		);
	}

	private CardBenefitUsageResponse emptyUsage() {
		return new CardBenefitUsageResponse(0L, 0L, 0L, 0L, 0L, 0L);
	}
}
