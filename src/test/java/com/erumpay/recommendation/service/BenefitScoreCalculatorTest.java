package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitUsageResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScore;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScoreContext;
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
			List.of(tier)
		);
	}

	private CardBenefitTierResponse tier(BigDecimal rate, Long flatAmount, String tierDesc) {
		return new CardBenefitTierResponse(
			1L,
			0L,
			null,
			rate,
			flatAmount,
			null,
			null,
			null,
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
