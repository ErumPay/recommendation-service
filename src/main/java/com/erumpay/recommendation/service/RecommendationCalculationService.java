package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationResponse;
import com.erumpay.recommendation.dto.BenefitSplitRecommendationResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RecommendationCalculationService {

	private static final String REASON_NO_PAYABLE_CARD = "NO_PAYABLE_CARD";

	private final MerchantCategoryResolverService merchantCategoryResolverService;
	private final CardRecommendationSourceService cardRecommendationSourceService;
	private final BenefitSingleRecommendationService benefitSingleRecommendationService;
	private final PerfSingleRecommendationService perfSingleRecommendationService;
	private final BenefitSplitRecommendationService benefitSplitRecommendationService;
	private final PerfSplitRecommendationService perfSplitRecommendationService;
	private final Clock clock;

	// [be] 이준혁 260528 0732 | payment-service 내부 API 요청을 4개 추천 전략의 고정 순서 응답으로 통합한다.
	public RecommendationCalculateResponse calculate(RecommendationCalculateRequest request) {
		validateRequest(request);
		LocalDateTime recommendedAt = LocalDateTime.now(clock);
		ServiceCategory paymentCategory = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest(request.merchantName(), request.mccCode())
		).serviceCategory();
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
		return new RecommendationCalculateResponse(
			request.paymentId(),
			recommendedAt,
			List.of(
				toResult(benefitSingleRecommendationService.calculate(source, context)),
				toResult(perfSingleRecommendationService.calculate(source, context)),
				toResult(benefitSplitRecommendationService.calculate(source, context)),
				toResult(perfSplitRecommendationService.calculate(source, context))
			),
			null
		);
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
