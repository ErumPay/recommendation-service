package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erumpay.recommendation.domain.enums.MerchantCategoryMatchedBy;
import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationResponse;
import com.erumpay.recommendation.dto.BenefitSplitRecommendationResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveResponse;
import com.erumpay.recommendation.dto.PerfSingleRecommendationResponse;
import com.erumpay.recommendation.dto.PerfSplitRecommendationResponse;
import com.erumpay.recommendation.dto.RecommendationCalculateRequest;
import com.erumpay.recommendation.dto.RecommendationCalculateResponse;
import com.erumpay.recommendation.dto.RecommendationStrategyResultResponse;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScoreContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationCalculationServiceTest {

	@Mock
	private MerchantCategoryResolverService merchantCategoryResolverService;

	@Mock
	private CardRecommendationSourceService cardRecommendationSourceService;

	@Mock
	private BenefitSingleRecommendationService benefitSingleRecommendationService;

	@Mock
	private PerfSingleRecommendationService perfSingleRecommendationService;

	@Mock
	private BenefitSplitRecommendationService benefitSplitRecommendationService;

	@Mock
	private PerfSplitRecommendationService perfSplitRecommendationService;

	@Mock
	private AiBestSelectorService aiBestSelectorService;

	private Clock clock;

	private RecommendationCalculationService recommendationCalculationService;

	@BeforeEach
	void setUp() {
		clock = Clock.fixed(
			Instant.parse("2026-05-26T01:00:00Z"),
			ZoneId.of("Asia/Seoul")
		);
		recommendationCalculationService = newService(Runnable::run);
	}

	@Test
	void calculateReturnsFourStrategyResultsInFixedOrderWithSharedSource() {
		CardRecommendationSourceResponse source = source(List.of(card(1L)));
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L)).thenReturn(source);
		when(benefitSingleRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new BenefitSingleRecommendationResponse("BENEFIT_SINGLE", 100L, List.of(), null));
		when(perfSingleRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new PerfSingleRecommendationResponse("PERF_SINGLE", 200L, List.of(), null));
		when(benefitSplitRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new BenefitSplitRecommendationResponse("BENEFIT_SPLIT", 300L, List.of(), null));
		when(perfSplitRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new PerfSplitRecommendationResponse("PERF_SPLIT", 400L, List.of(), null));
		when(aiBestSelectorService.applyBest(any(), any(), same(source), any()))
			.thenAnswer(invocation -> {
				List<RecommendationStrategyResultResponse> results = invocation.getArgument(3);
				assertThat(results).extracting("strategyType")
					.containsExactly("BENEFIT_SINGLE", "PERF_SINGLE", "BENEFIT_SPLIT", "PERF_SPLIT");
				return results;
			});

		RecommendationCalculateResponse response = recommendationCalculationService.calculate(request());

		assertThat(response.paymentId()).isEqualTo(123L);
		assertThat(response.recommendedAt()).isEqualTo(LocalDateTime.parse("2026-05-26T10:00:00"));
		assertThat(response.reason()).isNull();
		assertThat(response.results()).extracting("strategyType")
			.containsExactly("BENEFIT_SINGLE", "PERF_SINGLE", "BENEFIT_SPLIT", "PERF_SPLIT");
		assertThat(response.results()).extracting("totalBenefitAmount")
			.containsExactly(100L, 200L, 300L, 400L);
		verify(cardRecommendationSourceService).getRecommendationSource(10L);
		verify(aiBestSelectorService).applyBest(any(), any(), same(source), any());
	}

	@Test
	void calculateKeepsFixedOrderWhenStrategiesCompleteOutOfOrder() {
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			recommendationCalculationService = newService(executor);
			CardRecommendationSourceResponse source = source(List.of(card(1L)));
			CountDownLatch benefitSingleStarted = new CountDownLatch(1);
			CountDownLatch otherStrategiesFinished = new CountDownLatch(3);
			givenCategory(ServiceCategory.CAFE);
			when(cardRecommendationSourceService.getRecommendationSource(10L)).thenReturn(source);
			when(benefitSingleRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
				.thenAnswer(invocation -> {
					benefitSingleStarted.countDown();
					await(otherStrategiesFinished);
					return new BenefitSingleRecommendationResponse("BENEFIT_SINGLE", 100L, List.of(), null);
				});
			when(perfSingleRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
				.thenAnswer(invocation -> {
					await(benefitSingleStarted);
					otherStrategiesFinished.countDown();
					return new PerfSingleRecommendationResponse("PERF_SINGLE", 200L, List.of(), null);
				});
			when(benefitSplitRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
				.thenAnswer(invocation -> {
					await(benefitSingleStarted);
					otherStrategiesFinished.countDown();
					return new BenefitSplitRecommendationResponse("BENEFIT_SPLIT", 300L, List.of(), null);
				});
			when(perfSplitRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
				.thenAnswer(invocation -> {
					await(benefitSingleStarted);
					otherStrategiesFinished.countDown();
					return new PerfSplitRecommendationResponse("PERF_SPLIT", 400L, List.of(), null);
				});
			when(aiBestSelectorService.applyBest(any(), any(), same(source), any()))
				.thenAnswer(invocation -> invocation.getArgument(3));

			RecommendationCalculateResponse response = recommendationCalculationService.calculate(request());

			assertThat(response.results()).extracting("strategyType")
				.containsExactly("BENEFIT_SINGLE", "PERF_SINGLE", "BENEFIT_SPLIT", "PERF_SPLIT");
			assertThat(response.results()).extracting("totalBenefitAmount")
				.containsExactly(100L, 200L, 300L, 400L);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void calculateUnwrapsStrategyFailureAndSkipsBestSelector() {
		CardRecommendationSourceResponse source = source(List.of(card(1L)));
		RuntimeException failure = new IllegalStateException("strategy failed");
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L)).thenReturn(source);
		when(benefitSingleRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenThrow(new CompletionException(new CompletionException(failure)));
		when(perfSingleRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new PerfSingleRecommendationResponse("PERF_SINGLE", 200L, List.of(), null));
		when(benefitSplitRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new BenefitSplitRecommendationResponse("BENEFIT_SPLIT", 300L, List.of(), null));
		when(perfSplitRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new PerfSplitRecommendationResponse("PERF_SPLIT", 400L, List.of(), null));

		assertThatThrownBy(() -> recommendationCalculationService.calculate(request()))
			.isSameAs(failure);

		verify(aiBestSelectorService, never()).applyBest(any(), any(), any(), any());
	}

	@Test
	void calculatePreservesSplitFallbackStrategyTypes() {
		CardRecommendationSourceResponse source = source(List.of(card(1L)));
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L)).thenReturn(source);
		when(benefitSingleRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new BenefitSingleRecommendationResponse("BENEFIT_SINGLE", 100L, List.of(), null));
		when(perfSingleRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new PerfSingleRecommendationResponse("PERF_SINGLE", 200L, List.of(), null));
		when(benefitSplitRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new BenefitSplitRecommendationResponse("BENEFIT_SPLIT", 0L, List.of(), "CARD_DEFAULT_MISSING"));
		when(perfSplitRecommendationService.calculate(same(source), any(BenefitScoreContext.class)))
			.thenReturn(new PerfSplitRecommendationResponse("PERF_SPLIT", 0L, List.of(), "CARD_DEFAULT_MISSING"));
		when(aiBestSelectorService.applyBest(any(), any(), same(source), any()))
			.thenAnswer(invocation -> invocation.getArgument(3));

		RecommendationCalculateResponse response = recommendationCalculationService.calculate(request());

		assertThat(response.results()).extracting("strategyType")
			.containsExactly("BENEFIT_SINGLE", "PERF_SINGLE", "BENEFIT_SPLIT", "PERF_SPLIT");
		assertThat(response.results()).extracting("reason")
			.containsExactly(null, null, "CARD_DEFAULT_MISSING", "CARD_DEFAULT_MISSING");
	}

	@Test
	void calculateReturnsTopLevelNoPayableCardWhenSourceCardsAreEmpty() {
		givenCategory(ServiceCategory.CAFE);
		when(cardRecommendationSourceService.getRecommendationSource(10L)).thenReturn(source(List.of()));

		RecommendationCalculateResponse response = recommendationCalculationService.calculate(request());

		assertThat(response.results()).isEmpty();
		assertThat(response.reason()).isEqualTo("NO_PAYABLE_CARD");
		verify(benefitSingleRecommendationService, never()).calculate(any(), any());
		verify(perfSingleRecommendationService, never()).calculate(any(), any());
		verify(benefitSplitRecommendationService, never()).calculate(any(), any());
		verify(perfSplitRecommendationService, never()).calculate(any(), any());
	}

	@Test
	void calculateRejectsInvalidPaymentId() {
		assertThatThrownBy(() -> recommendationCalculationService.calculate(
			new RecommendationCalculateRequest(0L, 10L, "Starbucks Gangnam", "5811", 15_000L)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("paymentId must be positive");
	}

	private RecommendationCalculationService newService(Executor executor) {
		return new RecommendationCalculationService(
			merchantCategoryResolverService,
			cardRecommendationSourceService,
			benefitSingleRecommendationService,
			perfSingleRecommendationService,
			benefitSplitRecommendationService,
			perfSplitRecommendationService,
			aiBestSelectorService,
			clock,
			executor
		);
	}

	private void await(CountDownLatch latch) throws InterruptedException {
		assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
	}

	private void givenCategory(ServiceCategory serviceCategory) {
		when(merchantCategoryResolverService.resolve(any()))
			.thenReturn(new MerchantCategoryResolveResponse(
				"Starbucks Gangnam",
				"5811",
				serviceCategory,
				MerchantCategoryMatchedBy.MCC,
				null
			));
	}

	private RecommendationCalculateRequest request() {
		return new RecommendationCalculateRequest(123L, 10L, "Starbucks Gangnam", "5811", 15_000L);
	}

	private CardRecommendationSourceResponse source(List<CardRecommendationSourceCardResponse> cards) {
		return new CardRecommendationSourceResponse(10L, "202604", cards);
	}

	private CardRecommendationSourceCardResponse card(Long cardId) {
		return new CardRecommendationSourceCardResponse(
			cardId,
			cardId * 100L,
			"Test Card Company",
			"Test Card " + cardId,
			"1234-****-****-" + cardId,
			true,
			0L,
			List.of()
		);
	}
}
