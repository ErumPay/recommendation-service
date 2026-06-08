package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erumpay.recommendation.client.AiBestSelectorClient;
import com.erumpay.recommendation.domain.enums.MerchantCategoryMatchedBy;
import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.AiBestSelectionContext;
import com.erumpay.recommendation.dto.AiBestSelectionResult;
import com.erumpay.recommendation.dto.MerchantCategoryResolveResponse;
import com.erumpay.recommendation.dto.PaymentUsageSummaryResponse;
import com.erumpay.recommendation.dto.RecommendationCalculateRequest;
import com.erumpay.recommendation.dto.RecommendationStrategyResultResponse;
import com.erumpay.recommendation.dto.RecommendedCardResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiBestSelectorServiceTest {

	@Mock
	private PaymentUsageSummaryService paymentUsageSummaryService;

	@Mock
	private MerchantCategoryResolverService merchantCategoryResolverService;

	@Mock
	private AiBestSelectorClient aiBestSelectorClient;

	@Test
	void disabledSelectorUsesDeterministicFallback() {
		AiBestSelectorService service = service(properties(false, "", false));

		List<RecommendationStrategyResultResponse> selected = service.applyBest(
			request(),
			currentMerchant(),
			source(),
			results()
		);

		assertThat(bestStrategyType(selected)).isEqualTo("BENEFIT_SINGLE");
		verify(paymentUsageSummaryService, never()).getPreviousMonthSummary(any());
		verify(aiBestSelectorClient, never()).select(any());
	}

	@Test
	void enabledSelectorOverridesFallbackWhenAiReturnsValidStrategy() {
		AiBestSelectorService service = service(properties(true, "test-key", true));
		when(aiBestSelectorClient.provider()).thenReturn("gemini");
		when(paymentUsageSummaryService.getPreviousMonthSummary(10L)).thenReturn(Optional.of(usageSummary()));
		when(merchantCategoryResolverService.resolve(any()))
			.thenReturn(new MerchantCategoryResolveResponse(
				"스타벅스 강남점",
				null,
				ServiceCategory.CAFE,
				MerchantCategoryMatchedBy.KEYWORD,
				"스타벅스"
			));
		when(aiBestSelectorClient.select(any(AiBestSelectionContext.class)))
			.thenReturn(Optional.of(new AiBestSelectionResult("PERF_SINGLE", "실적 달성 가능성이 높음")));

		List<RecommendationStrategyResultResponse> selected = service.applyBest(
			request(),
			currentMerchant(),
			source(),
			results()
		);

		assertThat(bestStrategyType(selected)).isEqualTo("PERF_SINGLE");
	}

	@Test
	void invalidAiStrategyKeepsFallback() {
		AiBestSelectorService service = service(properties(true, "test-key", true));
		when(aiBestSelectorClient.provider()).thenReturn("gemini");
		when(paymentUsageSummaryService.getPreviousMonthSummary(10L)).thenReturn(Optional.of(usageSummary()));
		when(merchantCategoryResolverService.resolve(any()))
			.thenReturn(new MerchantCategoryResolveResponse(
				"스타벅스 강남점",
				null,
				ServiceCategory.CAFE,
				MerchantCategoryMatchedBy.KEYWORD,
				"스타벅스"
			));
		when(aiBestSelectorClient.select(any(AiBestSelectionContext.class)))
			.thenReturn(Optional.of(new AiBestSelectionResult("UNKNOWN", "invalid")));

		List<RecommendationStrategyResultResponse> selected = service.applyBest(
			request(),
			currentMerchant(),
			source(),
			results()
		);

		assertThat(bestStrategyType(selected)).isEqualTo("BENEFIT_SINGLE");
	}

	@Test
	void singleCardBenefitSplitSelectionIsNormalizedToBenefitSingleWhenBenefitIsNotHigher() {
		AiBestSelectorService service = service(properties(true, "test-key", true));
		when(aiBestSelectorClient.provider()).thenReturn("gemini");
		when(paymentUsageSummaryService.getPreviousMonthSummary(10L)).thenReturn(Optional.of(usageSummary()));
		when(merchantCategoryResolverService.resolve(any()))
			.thenReturn(new MerchantCategoryResolveResponse(
				"스타벅스 강남점",
				null,
				ServiceCategory.CAFE,
				MerchantCategoryMatchedBy.KEYWORD,
				"스타벅스"
			));
		when(aiBestSelectorClient.select(any(AiBestSelectionContext.class)))
			.thenReturn(Optional.of(new AiBestSelectionResult("BENEFIT_SPLIT", "단일보다 높다고 판단")));

		List<RecommendationStrategyResultResponse> selected = service.applyBest(
			request(),
			currentMerchant(),
			source(),
			results()
		);

		assertThat(bestStrategyType(selected)).isEqualTo("BENEFIT_SINGLE");
	}

	@Test
	void multiCardBenefitSplitSelectionIsKeptWhenBenefitIsHigherThanBenefitSingle() {
		AiBestSelectorService service = service(properties(true, "test-key", true));
		when(aiBestSelectorClient.provider()).thenReturn("gemini");
		when(paymentUsageSummaryService.getPreviousMonthSummary(10L)).thenReturn(Optional.of(usageSummary()));
		when(merchantCategoryResolverService.resolve(any()))
			.thenReturn(new MerchantCategoryResolveResponse(
				"스타벅스 강남점",
				null,
				ServiceCategory.CAFE,
				MerchantCategoryMatchedBy.KEYWORD,
				"스타벅스"
			));
		when(aiBestSelectorClient.select(any(AiBestSelectionContext.class)))
			.thenReturn(Optional.of(new AiBestSelectionResult("BENEFIT_SPLIT", "분할 혜택이 더 큼")));

		List<RecommendationStrategyResultResponse> selected = service.applyBest(
			request(),
			currentMerchant(),
			source(),
			List.of(
				result("BENEFIT_SINGLE", 100L, List.of(card(1L)), null),
				result("PERF_SINGLE", 0L, List.of(card(2L)), null),
				result("BENEFIT_SPLIT", 200L, List.of(card(3L), card(4L)), null),
				result("PERF_SPLIT", 0L, List.of(card(5L)), null)
			)
		);

		assertThat(bestStrategyType(selected)).isEqualTo("BENEFIT_SPLIT");
	}

	@Test
	void emptyUsageSummaryKeepsFallbackAndSkipsGemini() {
		AiBestSelectorService service = service(properties(true, "test-key", true));
		when(paymentUsageSummaryService.getPreviousMonthSummary(10L)).thenReturn(Optional.empty());

		List<RecommendationStrategyResultResponse> selected = service.applyBest(
			request(),
			currentMerchant(),
			source(),
			results()
		);

		assertThat(bestStrategyType(selected)).isEqualTo("BENEFIT_SINGLE");
		verify(aiBestSelectorClient, never()).select(any());
	}

	@Test
	void aiContextExcludesTierDescAndLimitsFutureBenefits() throws Exception {
		AiBestSelectorService service = service(properties(true, "test-key", true));
		when(aiBestSelectorClient.provider()).thenReturn("gemini");
		when(paymentUsageSummaryService.getPreviousMonthSummary(10L)).thenReturn(Optional.of(usageSummary()));
		when(merchantCategoryResolverService.resolve(any()))
			.thenReturn(new MerchantCategoryResolveResponse(
				"Starbucks Gangnam",
				null,
				ServiceCategory.CAFE,
				MerchantCategoryMatchedBy.KEYWORD,
				"STARBUCKS"
			));
		ArgumentCaptor<AiBestSelectionContext> contextCaptor =
			ArgumentCaptor.forClass(AiBestSelectionContext.class);
		when(aiBestSelectorClient.select(contextCaptor.capture())).thenReturn(Optional.empty());

		service.applyBest(
			request(),
			new MerchantCategoryResolveResponse(
				"Starbucks Gangnam",
				"5814",
				ServiceCategory.CAFE,
				MerchantCategoryMatchedBy.KEYWORD,
				"STARBUCKS"
			),
			sourceWithFutureBenefits(),
			results()
		);

		AiBestSelectionContext context = contextCaptor.getValue();
		List<AiBestSelectionContext.FutureBenefitContext> futureBenefits =
			context.strategies().getFirst().cards().getFirst().futureBenefits();
		assertThat(futureBenefits)
			.hasSize(5)
			.allMatch(benefit -> "CAFE".equals(benefit.serviceCategory()));
		String contextJson = new ObjectMapper().writeValueAsString(context);
		assertThat(contextJson)
			.doesNotContain("tierDesc")
			.doesNotContain("LONG_TIER_DESCRIPTION");
	}

	@Test
	void enabledSelectorRequiresApiKey() {
		AiBestSelectorService service = service(properties(true, "", true));

		assertThatThrownBy(service::validateConfig)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("RECOMMENDATION_AI_API_KEY or provider API key is required when AI selector is enabled");
	}

	@Test
	void enabledFreeTierRequiresDummyDataConfirmation() {
		AiBestSelectorService service = service(properties(true, "test-key", false));

		assertThatThrownBy(service::validateConfig)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("RECOMMENDATION_AI_FREE_TIER_DUMMY_DATA_CONFIRMED=true is required for AI Free tier");
	}

	@Test
	void enabledSelectorRejectsUnsupportedDataTier() {
		AiBestSelectorService service = service(properties(
			true,
			"test-key",
			"PAIID",
			false
		));

		assertThatThrownBy(service::validateConfig)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("RECOMMENDATION_AI_DATA_TIER must be FREE or PAID");
	}

	@Test
	void enabledPaidTierDoesNotRequireFreeTierConfirmation() {
		AiBestSelectorService service = service(properties(
			true,
			"test-key",
			"PAID",
			false
		));

		service.validateConfig();
	}

	private AiBestSelectorService service(AiSelectorProperties properties) {
		return new AiBestSelectorService(
			paymentUsageSummaryService,
			merchantCategoryResolverService,
			List.of(aiBestSelectorClient),
			properties
		);
	}

	private AiSelectorProperties properties(boolean enabled, String apiKey, boolean freeTierConfirmed) {
		return properties(enabled, apiKey, "FREE", freeTierConfirmed);
	}

	private AiSelectorProperties properties(
		boolean enabled,
		String apiKey,
		String dataTier,
		boolean freeTierConfirmed
	) {
		return new AiSelectorProperties(
			enabled,
			"gemini",
			apiKey,
			null,
			1500L,
			20,
			false,
			false,
			dataTier,
			freeTierConfirmed,
			new AiSelectorProperties.ProviderProperties(
				null,
				"gemini-2.5-flash-lite",
				"https://generativelanguage.googleapis.com"
			),
			new AiSelectorProperties.ProviderProperties(
				null,
				"gpt-4o-mini",
				"https://api.openai.com"
			)
		);
	}

	private String bestStrategyType(List<RecommendationStrategyResultResponse> results) {
		return results.stream()
			.filter(RecommendationStrategyResultResponse::isBest)
			.map(RecommendationStrategyResultResponse::strategyType)
			.findFirst()
			.orElse(null);
	}

	private List<RecommendationStrategyResultResponse> results() {
		return List.of(
			result("BENEFIT_SINGLE", card(1L), null),
			result("PERF_SINGLE", card(2L), null),
			result("BENEFIT_SPLIT", card(3L), null),
			result("PERF_SPLIT", card(4L), null)
		);
	}

	private RecommendationStrategyResultResponse result(
		String strategyType,
		RecommendedCardResponse card,
		String reason
	) {
		return new RecommendationStrategyResultResponse(strategyType, 100L, List.of(card), reason);
	}

	private RecommendationStrategyResultResponse result(
		String strategyType,
		Long totalBenefitAmount,
		List<RecommendedCardResponse> cards,
		String reason
	) {
		return new RecommendationStrategyResultResponse(strategyType, totalBenefitAmount, cards, reason);
	}

	private RecommendedCardResponse card(Long cardId) {
		return new RecommendedCardResponse(
			cardId,
			cardId * 100L,
			"Test Card Company",
			"Test Card " + cardId,
			"https://example.com/card.png",
			"1234-****-****-" + cardId,
			10_000L,
			100L,
			0L,
			0L,
			100L,
			null,
			90_000L,
			100_000L,
			10_000L,
			100_000L,
			true,
			List.of()
		);
	}

	private RecommendationCalculateRequest request() {
		return new RecommendationCalculateRequest(123L, 10L, "스타벅스 강남점", "5814", 10_000L);
	}

	private MerchantCategoryResolveResponse currentMerchant() {
		return new MerchantCategoryResolveResponse(
			"스타벅스 강남점",
			"5814",
			ServiceCategory.CAFE,
			MerchantCategoryMatchedBy.KEYWORD,
			"스타벅스"
		);
	}

	private CardRecommendationSourceResponse source() {
		return new CardRecommendationSourceResponse(
			10L,
			"202605",
			List.of(new CardRecommendationSourceCardResponse(
				1L,
				100L,
				"Test Card Company",
				"Test Card 1",
				"https://example.com/card.png",
				"1234-****-****-0001",
				true,
				90_000L,
				List.of()
			))
		);
	}

	private CardRecommendationSourceResponse sourceWithFutureBenefits() {
		return new CardRecommendationSourceResponse(
			10L,
			"202605",
			List.of(new CardRecommendationSourceCardResponse(
				1L,
				100L,
				"Test Card Company",
				"Test Card 1",
				"https://example.com/card.png",
				"1234-****-****-0001",
				true,
				90_000L,
				List.of(
					benefit("CAFE", List.of("STARBUCKS"), BigDecimal.valueOf(10), 1_000L, 5_000L, "LONG_TIER_DESCRIPTION_1"),
					benefit("CAFE", List.of("POLBASSET"), BigDecimal.valueOf(10), 1_000L, 5_000L, "LONG_TIER_DESCRIPTION_2"),
					benefit("CAFE", List.of(), BigDecimal.valueOf(5), 500L, 3_000L, "LONG_TIER_DESCRIPTION_3"),
					benefit("CAFE", List.of("STARBUCKS"), BigDecimal.valueOf(3), 300L, 2_000L, "LONG_TIER_DESCRIPTION_4"),
					benefit("CAFE", List.of("STARBUCKS"), BigDecimal.valueOf(2), 200L, 1_000L, "LONG_TIER_DESCRIPTION_5"),
					benefit("CAFE", List.of("STARBUCKS"), BigDecimal.ONE, 100L, 500L, "LONG_TIER_DESCRIPTION_6"),
					benefit("SHOPPING", List.of("MART"), BigDecimal.valueOf(20), 10_000L, 50_000L,
						"LONG_TIER_DESCRIPTION_7")
				)
			))
		);
	}

	private CardBenefitResponse benefit(
		String serviceCategory,
		List<String> brandNames,
		BigDecimal rate,
		Long maxBenefitPerUse,
		Long monthlyLimitAmount,
		String tierDesc
	) {
		return new CardBenefitResponse(
			null,
			serviceCategory,
			"DISCOUNT",
			null,
			null,
			null,
			null,
			null,
			brandNames,
			null,
			List.of(tier(rate, maxBenefitPerUse, monthlyLimitAmount, tierDesc))
		);
	}

	private CardBenefitTierResponse tier(
		BigDecimal rate,
		Long maxBenefitPerUse,
		Long monthlyLimitAmount,
		String tierDesc
	) {
		return new CardBenefitTierResponse(
			null,
			0L,
			null,
			rate,
			null,
			maxBenefitPerUse,
			null,
			null,
			null,
			monthlyLimitAmount,
			null,
			null,
			tierDesc
		);
	}

	private PaymentUsageSummaryResponse usageSummary() {
		return new PaymentUsageSummaryResponse(
			10L,
			java.time.LocalDate.parse("2026-05-01"),
			java.time.LocalDate.parse("2026-05-31"),
			50_000L,
			5L,
			List.of(new PaymentUsageSummaryResponse.MerchantUsage("스타벅스 강남점", 5L, 50_000L)),
			List.of(new PaymentUsageSummaryResponse.CardUsage(1L, 5L, 50_000L))
		);
	}
}
