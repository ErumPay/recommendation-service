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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
	void paymentUsageSummaryFailureKeepsFallbackAndSkipsGemini() {
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

	@ParameterizedTest
	@CsvSource({
		"BENEFIT_SINGLE, BENEFIT_SINGLE",
		"BENEFIT_SPLIT, BENEFIT_SPLIT",
		"PERF_SINGLE, PERF_SINGLE",
		"PERF_SPLIT, PERF_SPLIT"
	})
	void emptyPaymentUsageWithPerformanceContextAcceptsAiStrategy(String aiStrategy, String expectedStrategy) {
		AiBestSelectionContext context = assertAiSelection(
			Optional.of(emptyUsageSummary()),
			false,
			aiStrategy,
			expectedStrategy,
			selectionResultsWithPerformanceContext()
		);

		assertThat(context.usageSummary().hasPaymentUsage()).isFalse();
		assertThat(hasPerformanceContext(context)).isTrue();
	}

	@ParameterizedTest
	@CsvSource({
		"BENEFIT_SINGLE, BENEFIT_SINGLE",
		"BENEFIT_SPLIT, BENEFIT_SPLIT",
		"PERF_SINGLE, PERF_SINGLE",
		"PERF_SPLIT, PERF_SPLIT"
	})
	void paymentUsageWithPerformanceContextAcceptsAiStrategy(String aiStrategy, String expectedStrategy) {
		AiBestSelectionContext context = assertAiSelection(
			Optional.of(usageSummary()),
			true,
			aiStrategy,
			expectedStrategy,
			selectionResultsWithPerformanceContext()
		);

		assertThat(context.usageSummary().hasPaymentUsage()).isTrue();
		assertThat(hasPerformanceContext(context)).isTrue();
	}

	@ParameterizedTest
	@CsvSource({
		"PERF_SINGLE, PERF_SINGLE",
		"PERF_SPLIT, PERF_SPLIT"
	})
	void emptyPaymentUsageAndZeroCurrentPerformanceCanSelectPerformanceStrategy(
		String aiStrategy,
		String expectedStrategy
	) {
		AiBestSelectionContext context = assertAiSelection(
			Optional.of(emptyUsageSummary()),
			false,
			aiStrategy,
			expectedStrategy,
			selectionResultsWithZeroCurrentPerformance()
		);

		assertThat(context.usageSummary().hasPaymentUsage()).isFalse();
		List<AiBestSelectionContext.Card> performanceCards = context.strategies().stream()
			.filter(strategy -> aiStrategy.equals(strategy.strategyType()))
			.flatMap(strategy -> strategy.cards().stream())
			.toList();
		assertThat(performanceCards)
			.isNotEmpty()
			.allSatisfy(card -> {
				assertThat(card.currentPerformanceAmount()).isZero();
				assertThat(card.totalBenefitAmount()).isPositive();
				assertThat(card.targetPerformanceAmount()).isNotNull();
				assertThat(card.futureBenefits()).isNotEmpty();
			});
	}

	@Test
	void emptyPaymentUsageWithoutPerformanceContextKeepsFallbackAndSkipsAi() {
		AiBestSelectorService service = service(properties(true, "test-key", true));
		when(paymentUsageSummaryService.getPreviousMonthSummary(10L)).thenReturn(Optional.of(emptyUsageSummary()));

		List<RecommendationStrategyResultResponse> selected = service.applyBest(
			request(),
			currentMerchant(),
			sourceWithoutBenefits(),
			resultsWithoutPerformanceContext()
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

	private AiBestSelectionContext assertAiSelection(
		Optional<PaymentUsageSummaryResponse> usageSummary,
		boolean hasPaymentUsage,
		String aiStrategy,
		String expectedStrategy,
		List<RecommendationStrategyResultResponse> results
	) {
		AiBestSelectorService service = service(properties(true, "test-key", true));
		when(aiBestSelectorClient.provider()).thenReturn("gemini");
		when(paymentUsageSummaryService.getPreviousMonthSummary(10L)).thenReturn(usageSummary);
		if (hasPaymentUsage) {
			when(merchantCategoryResolverService.resolve(any()))
				.thenReturn(new MerchantCategoryResolveResponse(
					"스타벅스 강남점",
					null,
					ServiceCategory.CAFE,
					MerchantCategoryMatchedBy.KEYWORD,
					"스타벅스"
				));
		}
		ArgumentCaptor<AiBestSelectionContext> contextCaptor =
			ArgumentCaptor.forClass(AiBestSelectionContext.class);
		when(aiBestSelectorClient.select(contextCaptor.capture()))
			.thenReturn(Optional.of(new AiBestSelectionResult(aiStrategy, "테스트 선택")));

		List<RecommendationStrategyResultResponse> selected = service.applyBest(
			request(),
			currentMerchant(),
			sourceWithPerformanceBenefits(),
			results
		);

		assertThat(bestStrategyType(selected)).isEqualTo(expectedStrategy);
		return contextCaptor.getValue();
	}

	private boolean hasPerformanceContext(AiBestSelectionContext context) {
		return context.strategies().stream()
			.flatMap(strategy -> strategy.cards().stream())
			.anyMatch(card -> card.targetPerformanceAmount() != null
				|| card.remainingToTarget() != null
				|| Boolean.TRUE.equals(card.willReachTarget())
				|| !card.futureBenefits().isEmpty());
	}

	private List<RecommendationStrategyResultResponse> results() {
		return List.of(
			result("BENEFIT_SINGLE", card(1L), null),
			result("PERF_SINGLE", card(2L), null),
			result("BENEFIT_SPLIT", card(3L), null),
			result("PERF_SPLIT", card(4L), null)
		);
	}

	private List<RecommendationStrategyResultResponse> selectionResultsWithPerformanceContext() {
		return List.of(
			result("BENEFIT_SINGLE", 900L, List.of(performanceCard(1L, 90_000L, 300_000L, 210_000L, 100_000L, false,
				900L)), null),
			result("PERF_SINGLE", 700L, List.of(performanceCard(2L, 90_000L, 300_000L, 210_000L, 290_000L, false,
				700L)), null),
			result("BENEFIT_SPLIT", 1_100L, List.of(
				performanceCard(3L, 90_000L, 300_000L, 210_000L, 180_000L, false, 600L),
				performanceCard(4L, 20_000L, 300_000L, 280_000L, 120_000L, false, 500L)
			), null),
			result("PERF_SPLIT", 800L, List.of(
				performanceCard(2L, 90_000L, 300_000L, 210_000L, 300_000L, true, 500L),
				performanceCard(5L, 0L, 300_000L, 300_000L, 200_000L, false, 300L)
			), null)
		);
	}

	private List<RecommendationStrategyResultResponse> selectionResultsWithZeroCurrentPerformance() {
		return List.of(
			result("BENEFIT_SINGLE", 900L, List.of(performanceCard(1L, 0L, 300_000L, 300_000L, 20_000L, false,
				900L)), null),
			result("PERF_SINGLE", 500L, List.of(performanceCard(2L, 0L, 300_000L, 300_000L, 250_000L, false,
				500L)), null),
			result("BENEFIT_SPLIT", 1_100L, List.of(
				performanceCard(3L, 0L, 300_000L, 300_000L, 30_000L, false, 600L),
				performanceCard(4L, 0L, 300_000L, 300_000L, 20_000L, false, 500L)
			), null),
			result("PERF_SPLIT", 600L, List.of(
				performanceCard(2L, 0L, 300_000L, 300_000L, 250_000L, false, 300L),
				performanceCard(5L, 0L, 300_000L, 300_000L, 200_000L, false, 300L)
			), null)
		);
	}

	private List<RecommendationStrategyResultResponse> resultsWithoutPerformanceContext() {
		return List.of(
			result("BENEFIT_SINGLE", 900L, List.of(noPerformanceCard(1L, 900L)), null),
			result("PERF_SINGLE", 500L, List.of(noPerformanceCard(2L, 500L)), null),
			result("BENEFIT_SPLIT", 1_100L, List.of(noPerformanceCard(3L, 600L), noPerformanceCard(4L, 500L)), null),
			result("PERF_SPLIT", 600L, List.of(noPerformanceCard(5L, 600L)), null)
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
		return performanceCard(cardId, 90_000L, 100_000L, 10_000L, 100_000L, true, 100L);
	}

	private RecommendedCardResponse performanceCard(
		Long cardId,
		Long currentPerformanceAmount,
		Long targetPerformanceAmount,
		Long remainingToTarget,
		Long expectedPerformanceAmount,
		Boolean willReachTarget,
		Long totalBenefitAmount
	) {
		return new RecommendedCardResponse(
			cardId,
			cardId * 100L,
			"Test Card Company",
			"Test Card " + cardId,
			"https://example.com/card.png",
			"1234-****-****-" + cardId,
			10_000L,
			totalBenefitAmount,
			0L,
			0L,
			totalBenefitAmount,
			null,
			currentPerformanceAmount,
			targetPerformanceAmount,
			remainingToTarget,
			expectedPerformanceAmount,
			willReachTarget,
			List.of()
		);
	}

	private RecommendedCardResponse noPerformanceCard(Long cardId, Long totalBenefitAmount) {
		return new RecommendedCardResponse(
			cardId,
			cardId * 100L,
			"Test Card Company",
			"Test Card " + cardId,
			"https://example.com/card.png",
			"1234-****-****-" + cardId,
			10_000L,
			totalBenefitAmount,
			0L,
			0L,
			totalBenefitAmount,
			null,
			0L,
			null,
			null,
			10_000L,
			false,
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

	private CardRecommendationSourceResponse sourceWithPerformanceBenefits() {
		return new CardRecommendationSourceResponse(
			10L,
			"202605",
			List.of(
				sourceCardWithPerformanceBenefit(1L),
				sourceCardWithPerformanceBenefit(2L),
				sourceCardWithPerformanceBenefit(3L),
				sourceCardWithPerformanceBenefit(4L),
				sourceCardWithPerformanceBenefit(5L)
			)
		);
	}

	private CardRecommendationSourceResponse sourceWithoutBenefits() {
		return new CardRecommendationSourceResponse(
			10L,
			"202605",
			List.of(
				sourceCardWithoutBenefits(1L),
				sourceCardWithoutBenefits(2L),
				sourceCardWithoutBenefits(3L),
				sourceCardWithoutBenefits(4L),
				sourceCardWithoutBenefits(5L)
			)
		);
	}

	private CardRecommendationSourceCardResponse sourceCardWithPerformanceBenefit(Long cardId) {
		return new CardRecommendationSourceCardResponse(
			cardId,
			cardId * 100L,
			"Test Card Company",
			"Test Card " + cardId,
			"https://example.com/card.png",
			"1234-****-****-" + cardId,
			cardId == 1L,
			0L,
			List.of(benefitWithMinUsage(
				"CAFE",
				List.of("스타벅스"),
				BigDecimal.valueOf(15),
				10_000L,
				50_000L,
				200_000L,
				"PERFORMANCE_TIER_DESCRIPTION"
			))
		);
	}

	private CardRecommendationSourceCardResponse sourceCardWithoutBenefits(Long cardId) {
		return new CardRecommendationSourceCardResponse(
			cardId,
			cardId * 100L,
			"Test Card Company",
			"Test Card " + cardId,
			"https://example.com/card.png",
			"1234-****-****-" + cardId,
			cardId == 1L,
			0L,
			List.of()
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

	private CardBenefitResponse benefitWithMinUsage(
		String serviceCategory,
		List<String> brandNames,
		BigDecimal rate,
		Long maxBenefitPerUse,
		Long monthlyLimitAmount,
		Long minPrevMonthUsage,
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
			List.of(tierWithMinUsage(rate, maxBenefitPerUse, monthlyLimitAmount, minPrevMonthUsage, tierDesc))
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

	private CardBenefitTierResponse tierWithMinUsage(
		BigDecimal rate,
		Long maxBenefitPerUse,
		Long monthlyLimitAmount,
		Long minPrevMonthUsage,
		String tierDesc
	) {
		return new CardBenefitTierResponse(
			null,
			minPrevMonthUsage,
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

	private PaymentUsageSummaryResponse emptyUsageSummary() {
		return new PaymentUsageSummaryResponse(
			10L,
			java.time.LocalDate.parse("2026-05-01"),
			java.time.LocalDate.parse("2026-05-31"),
			0L,
			0L,
			List.of(),
			List.of()
		);
	}
}
