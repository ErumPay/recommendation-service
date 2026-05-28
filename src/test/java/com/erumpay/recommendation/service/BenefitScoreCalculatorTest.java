package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitUsageResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScore;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScoreContext;
import com.erumpay.recommendation.service.BenefitScoreCalculator.SplitBenefitScore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenefitScoreCalculatorTest {

	private final BenefitScoreCalculator calculator = new BenefitScoreCalculator();

	@Test
	void calculateSelectsBestSingleBenefitWithoutSummingCategoryAndAll() {
		CardRecommendationSourceCardResponse card = card(List.of(
			benefit(200L, "CAFE", "CASHBACK", tier(null, 2_050L, null)),
			benefit(201L, "ALL", "MILEAGE", tier(null, 100L, "[SHARED_LIMIT monthly]"))
		));

		BenefitScore score = calculator.calculate(card, context());

		assertThat(score.totalBenefitAmount()).isEqualTo(2_050L);
		assertThat(score.discountAmount()).isZero();
		assertThat(score.cashbackAmount()).isEqualTo(2_050L);
		assertThat(score.mileageAmount()).isZero();
		assertThat(score.warnings()).isEmpty();
	}

	@Test
	void calculateUsesBenefitIdWhenBestBenefitAmountsTieInSameCard() {
		CardRecommendationSourceCardResponse card = card(List.of(
			benefit(200L, "CAFE", "DISCOUNT", tier(null, 1_000L, null)),
			benefit(100L, "CAFE", "CASHBACK", tier(null, 1_000L, null))
		));

		BenefitScore score = calculator.calculate(card, context());

		assertThat(score.totalBenefitAmount()).isEqualTo(1_000L);
		assertThat(score.discountAmount()).isZero();
		assertThat(score.cashbackAmount()).isEqualTo(1_000L);
		assertThat(score.mileageAmount()).isZero();
	}

	@Test
	void calculateMapsSelectedBenefitTypeToMatchingAmountField() {
		BenefitScore discountScore = calculator.calculate(card(List.of(
			benefit(100L, "CAFE", "DISCOUNT", tier(null, 1_000L, null))
		)), context());
		BenefitScore mileageScore = calculator.calculate(card(List.of(
			benefit(200L, "CAFE", "MILEAGE", tier(null, 800L, null))
		)), context());

		assertThat(discountScore.discountAmount()).isEqualTo(1_000L);
		assertThat(discountScore.cashbackAmount()).isZero();
		assertThat(discountScore.mileageAmount()).isZero();
		assertThat(discountScore.totalBenefitAmount()).isEqualTo(1_000L);
		assertThat(mileageScore.discountAmount()).isZero();
		assertThat(mileageScore.cashbackAmount()).isZero();
		assertThat(mileageScore.mileageAmount()).isEqualTo(800L);
		assertThat(mileageScore.totalBenefitAmount()).isEqualTo(800L);
	}

	@Test
	void calculateKeepsOnlySelectedBenefitWarnings() {
		CardRecommendationSourceCardResponse card = card(List.of(
			benefit(
				100L,
				"CAFE",
				"DISCOUNT",
				null,
				"09:00",
				"10:05",
				"ALL",
				tier(null, 2_000L, null)
			),
			benefit(200L, "ALL", "CASHBACK", tier(null, 1_000L, "[SHARED_LIMIT monthly]"))
		));

		BenefitScore score = calculator.calculate(card, context());

		assertThat(score.totalBenefitAmount()).isEqualTo(2_000L);
		assertThat(score.discountAmount()).isEqualTo(2_000L);
		assertThat(score.cashbackAmount()).isZero();
		assertThat(score.warnings()).containsExactly("TIME_END_BOUNDARY_NEAR");
	}

	@Test
	void calculateSplitCandidatesBuildsFlatAndRateCandidates() {
		CardRecommendationSourceCardResponse card = card(List.of(
			benefit(100L, "CAFE", "DISCOUNT", 10_000L, null, null, "ALL", tier(null, 1_000L, null)),
			benefit(200L, "CAFE", "CASHBACK", tier(new BigDecimal("10.00"), null, 500L, null, null))
		));

		List<SplitBenefitScore> candidates = calculator.calculateSplitCandidates(card, context(), 20_000L);

		assertThat(candidates).hasSize(2);
		SplitBenefitScore flatCandidate = candidates.get(0);
		SplitBenefitScore rateCandidate = candidates.get(1);
		assertThat(flatCandidate.candidateAmount()).isEqualTo(10_000L);
		assertThat(flatCandidate.benefitAmount()).isEqualTo(1_000L);
		assertThat(flatCandidate.benefitEfficiency()).isEqualByComparingTo("0.1000000000");
		assertThat(rateCandidate.saturationAmount()).isEqualTo(5_000L);
		assertThat(rateCandidate.candidateAmount()).isEqualTo(5_000L);
		assertThat(rateCandidate.benefitAmount()).isEqualTo(500L);
	}

	@Test
	void calculateSplitCandidatesExcludesFlatWithoutPositiveMinAmountAndCountExhaustedRate() {
		CardRecommendationSourceCardResponse card = card(List.of(
			benefit(100L, "CAFE", "DISCOUNT", tier(null, 1_000L, null)),
			benefit(
				200L,
				"CAFE",
				"CASHBACK",
				null,
				null,
				null,
				"ALL",
				tier(new BigDecimal("10.00"), null, null, 1, null),
				new CardBenefitUsageResponse(0L, 1L, 0L, 0L, 0L, 0L)
			)
		));

		List<SplitBenefitScore> candidates = calculator.calculateSplitCandidates(card, context(), 20_000L);

		assertThat(candidates).isEmpty();
	}

	@Test
	void calculateSplitCandidatesUsesMinAmountWhenRateSaturationAmountIsLower() {
		CardRecommendationSourceCardResponse card = card(List.of(
			benefit(100L, "CAFE", "DISCOUNT", 10_000L, null, null, "ALL",
				tier(new BigDecimal("10.00"), null, 500L, null, null))
		));

		List<SplitBenefitScore> candidates = calculator.calculateSplitCandidates(card, context(), 20_000L);

		assertThat(candidates).hasSize(1);
		assertThat(candidates.getFirst().saturationAmount()).isEqualTo(5_000L);
		assertThat(candidates.getFirst().candidateAmount()).isEqualTo(10_000L);
		assertThat(candidates.getFirst().benefitAmount()).isEqualTo(500L);
	}

	@Test
	void calculateSplitCandidatesUsesRemainingAmountForUnlimitedRateBenefit() {
		CardRecommendationSourceCardResponse card = card(List.of(
			benefit(100L, "CAFE", "DISCOUNT", tier(new BigDecimal("10.00"), null, null))
		));

		List<SplitBenefitScore> candidates = calculator.calculateSplitCandidates(card, context(), 20_000L);

		assertThat(candidates).hasSize(1);
		assertThat(candidates.getFirst().remainingBenefitLimit()).isNull();
		assertThat(candidates.getFirst().saturationAmount()).isNull();
		assertThat(candidates.getFirst().candidateAmount()).isEqualTo(20_000L);
		assertThat(candidates.getFirst().benefitAmount()).isEqualTo(2_000L);
		assertThat(candidates.getFirst().benefitEfficiency()).isEqualByComparingTo("0.1000000000");
	}

	private BenefitScoreContext context() {
		return new BenefitScoreContext(
			ServiceCategory.CAFE,
			MerchantNameNormalizer.normalize("스타벅스 강남점"),
			10_000L,
			LocalDateTime.of(2026, 5, 26, 10, 0)
		);
	}

	private CardRecommendationSourceCardResponse card(List<CardBenefitResponse> benefits) {
		return new CardRecommendationSourceCardResponse(
			1L,
			100L,
			"테스트카드사",
			"테스트카드",
			"1234-****-****-0001",
			true,
			0L,
			benefits
		);
	}

	private CardBenefitResponse benefit(
		Long benefitId,
		String serviceCategory,
		String benefitType,
		CardBenefitTierResponse tier
	) {
		return benefit(benefitId, serviceCategory, benefitType, null, null, null, "ALL", tier);
	}

	private CardBenefitResponse benefit(
		Long benefitId,
		String serviceCategory,
		String benefitType,
		Long minAmount,
		String timeStart,
		String timeEnd,
		String dayCondition,
		CardBenefitTierResponse tier
	) {
		return benefit(
			benefitId,
			serviceCategory,
			benefitType,
			minAmount,
			timeStart,
			timeEnd,
			dayCondition,
			tier,
			emptyUsage()
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
		CardBenefitTierResponse tier,
		CardBenefitUsageResponse usage
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
			List.of(),
			usage,
			List.of(tier)
		);
	}

	private CardBenefitTierResponse tier(BigDecimal rate, Long flatAmount, String tierDesc) {
		return tier(rate, flatAmount, null, null, null, tierDesc);
	}

	private CardBenefitTierResponse tier(
		BigDecimal rate,
		Long flatAmount,
		Long maxBenefitPerUse,
		Integer dailyLimitCount,
		Long dailyLimitAmount
	) {
		return tier(rate, flatAmount, maxBenefitPerUse, dailyLimitCount, dailyLimitAmount, null);
	}

	private CardBenefitTierResponse tier(
		BigDecimal rate,
		Long flatAmount,
		Long maxBenefitPerUse,
		Integer dailyLimitCount,
		Long dailyLimitAmount,
		String tierDesc
	) {
		return new CardBenefitTierResponse(
			1L,
			0L,
			null,
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
