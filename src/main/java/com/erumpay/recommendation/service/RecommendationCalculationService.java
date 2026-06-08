package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationResponse;
import com.erumpay.recommendation.dto.BenefitSplitRecommendationResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.dto.PerfSingleRecommendationResponse;
import com.erumpay.recommendation.dto.PerfSplitRecommendationResponse;
import com.erumpay.recommendation.dto.RecommendationCalculateRequest;
import com.erumpay.recommendation.dto.RecommendationCalculateResponse;
import com.erumpay.recommendation.dto.RecommendationStrategyResultResponse;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScoreContext;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RecommendationCalculationService {

	private static final String REASON_NO_PAYABLE_CARD = "NO_PAYABLE_CARD";

	private final MerchantCategoryResolverService merchantCategoryResolverService;
	private final CardRecommendationSourceService cardRecommendationSourceService;
	private final BenefitSingleRecommendationService benefitSingleRecommendationService;
	private final PerfSingleRecommendationService perfSingleRecommendationService;
	private final BenefitSplitRecommendationService benefitSplitRecommendationService;
	private final PerfSplitRecommendationService perfSplitRecommendationService;
	private final AiBestSelectorService aiBestSelectorService;
	private final Clock clock;
	private final Executor recommendationStrategyExecutor;

	public RecommendationCalculationService(
		MerchantCategoryResolverService merchantCategoryResolverService,
		CardRecommendationSourceService cardRecommendationSourceService,
		BenefitSingleRecommendationService benefitSingleRecommendationService,
		PerfSingleRecommendationService perfSingleRecommendationService,
		BenefitSplitRecommendationService benefitSplitRecommendationService,
		PerfSplitRecommendationService perfSplitRecommendationService,
		AiBestSelectorService aiBestSelectorService,
		Clock clock,
		@Qualifier("recommendationStrategyExecutor") Executor recommendationStrategyExecutor
	) {
		this.merchantCategoryResolverService = merchantCategoryResolverService;
		this.cardRecommendationSourceService = cardRecommendationSourceService;
		this.benefitSingleRecommendationService = benefitSingleRecommendationService;
		this.perfSingleRecommendationService = perfSingleRecommendationService;
		this.benefitSplitRecommendationService = benefitSplitRecommendationService;
		this.perfSplitRecommendationService = perfSplitRecommendationService;
		this.aiBestSelectorService = aiBestSelectorService;
		this.clock = clock;
		this.recommendationStrategyExecutor = recommendationStrategyExecutor;
	}

	// [be] 이준혁 260528 0732 | payment-service 내부 API 요청을 4개 추천 전략의 고정 순서 응답으로 통합한다.
	public RecommendationCalculateResponse calculate(RecommendationCalculateRequest request) {
		validateRequest(request);
		LocalDateTime recommendedAt = LocalDateTime.now(clock);
		MerchantCategoryResolveResponse merchantCategory = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest(request.merchantName(), request.mccCode())
		);
		ServiceCategory paymentCategory = merchantCategory.serviceCategory();
		CardRecommendationSourceResponse source = cardRecommendationSourceService.getRecommendationSource(
			request.userId()
		);
		if (sourceCards(source).isEmpty()) {
			return noPayableCardResponse(request.paymentId(), recommendedAt);
		}

		BenefitScoreContext context = new BenefitScoreContext(
			paymentCategory,
			MerchantNameNormalizer.normalize(request.merchantName()),
			request.amount(),
			recommendedAt
		);
		List<RecommendationStrategyResultResponse> results = calculateStrategyResults(source, context);
		return new RecommendationCalculateResponse(
			request.paymentId(),
			recommendedAt,
			aiBestSelectorService.applyBest(request, merchantCategory, source, results),
			null
		);
	}

	private List<RecommendationStrategyResultResponse> calculateStrategyResults(
		CardRecommendationSourceResponse source,
		BenefitScoreContext context
	) {
		CompletableFuture<RecommendationStrategyResultResponse> benefitSingle = CompletableFuture.supplyAsync(
			() -> toResult(benefitSingleRecommendationService.calculate(source, context)),
			recommendationStrategyExecutor
		);
		CompletableFuture<RecommendationStrategyResultResponse> perfSingle = CompletableFuture.supplyAsync(
			() -> toResult(perfSingleRecommendationService.calculate(source, context)),
			recommendationStrategyExecutor
		);
		CompletableFuture<RecommendationStrategyResultResponse> benefitSplit = CompletableFuture.supplyAsync(
			() -> toResult(benefitSplitRecommendationService.calculate(source, context)),
			recommendationStrategyExecutor
		);
		CompletableFuture<RecommendationStrategyResultResponse> perfSplit = CompletableFuture.supplyAsync(
			() -> toResult(perfSplitRecommendationService.calculate(source, context)),
			recommendationStrategyExecutor
		);

		awaitAll(benefitSingle, perfSingle, benefitSplit, perfSplit);
		return List.of(
			join(benefitSingle),
			join(perfSingle),
			join(benefitSplit),
			join(perfSplit)
		);
	}

	@SafeVarargs
	private final void awaitAll(CompletableFuture<RecommendationStrategyResultResponse>... futures) {
		try {
			CompletableFuture.allOf(futures).join();
		} catch (CompletionException exception) {
			throw unwrap(exception);
		}
	}

	private RecommendationStrategyResultResponse join(
		CompletableFuture<RecommendationStrategyResultResponse> future
	) {
		try {
			return future.join();
		} catch (CompletionException exception) {
			throw unwrap(exception);
		}
	}

	private RuntimeException unwrap(Throwable exception) {
		Throwable cause = exception;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException)
			&& cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof RuntimeException runtimeException) {
			return runtimeException;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		return new IllegalStateException(cause);
	}

	private RecommendationCalculateResponse noPayableCardResponse(Long paymentId, LocalDateTime recommendedAt) {
		return new RecommendationCalculateResponse(paymentId, recommendedAt, List.of(), REASON_NO_PAYABLE_CARD);
	}

	private RecommendationStrategyResultResponse toResult(BenefitSingleRecommendationResponse response) {
		return new RecommendationStrategyResultResponse(
			response.strategyType(),
			response.totalBenefitAmount(),
			response.cards(),
			response.reason()
		);
	}

	private RecommendationStrategyResultResponse toResult(PerfSingleRecommendationResponse response) {
		return new RecommendationStrategyResultResponse(
			response.strategyType(),
			response.totalBenefitAmount(),
			response.cards(),
			response.reason()
		);
	}

	private RecommendationStrategyResultResponse toResult(BenefitSplitRecommendationResponse response) {
		return new RecommendationStrategyResultResponse(
			response.strategyType(),
			response.totalBenefitAmount(),
			response.cards(),
			response.reason()
		);
	}

	private RecommendationStrategyResultResponse toResult(PerfSplitRecommendationResponse response) {
		return new RecommendationStrategyResultResponse(
			response.strategyType(),
			response.totalBenefitAmount(),
			response.cards(),
			response.reason()
		);
	}

	private List<?> sourceCards(CardRecommendationSourceResponse source) {
		if (source == null || source.cards() == null) {
			return List.of();
		}
		return source.cards();
	}

	private void validateRequest(RecommendationCalculateRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("recommendation request is required");
		}
		if (request.paymentId() == null || request.paymentId() <= 0) {
			throw new IllegalArgumentException("paymentId must be positive");
		}
		if (request.userId() == null || request.userId() <= 0) {
			throw new IllegalArgumentException("userId must be positive");
		}
		if (!StringUtils.hasText(request.merchantName())) {
			throw new IllegalArgumentException("merchantName is required");
		}
		if (!StringUtils.hasText(request.mccCode())) {
			throw new IllegalArgumentException("mccCode is required");
		}
		if (request.amount() == null || request.amount() <= 0) {
			throw new IllegalArgumentException("amount must be positive");
		}
	}
}
