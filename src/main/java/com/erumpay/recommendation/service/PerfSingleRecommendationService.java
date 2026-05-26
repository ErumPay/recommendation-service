package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.dto.PerfSingleRecommendationRequest;
import com.erumpay.recommendation.dto.PerfSingleRecommendationResponse;
import com.erumpay.recommendation.dto.RecommendedCardResponse;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScore;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScoreContext;
import com.erumpay.recommendation.service.PerformanceTargetCalculator.PerformanceTargetScore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PerfSingleRecommendationService {

	private static final String STRATEGY_TYPE = "PERF_SINGLE";
	private static final String REASON_NO_PAYABLE_CARD = "NO_PAYABLE_CARD";
	private static final String REASON_CARD_DEFAULT_MISSING = "CARD_DEFAULT_MISSING";
	private static final String WARNING_NO_PERFORMANCE_TARGET = "NO_PERFORMANCE_TARGET";

	private final MerchantCategoryResolverService merchantCategoryResolverService;
	private final CardRecommendationSourceService cardRecommendationSourceService;
	private final BenefitScoreCalculator benefitScoreCalculator;
	private final PerformanceTargetCalculator performanceTargetCalculator;
	private final Clock clock;

	// [be] 이준혁 260526 1605 | 다음 목표 실적까지 남은 금액이 가장 가까운 단일 카드를 추천한다.
	public PerfSingleRecommendationResponse recommend(PerfSingleRecommendationRequest request) {
		validateRequest(request);
		ServiceCategory paymentCategory = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest(request.merchantName(), request.mccCode())
		).serviceCategory();
		CardRecommendationSourceResponse source = cardRecommendationSourceService.getRecommendationSource(
			request.userId()
		);
		List<CardRecommendationSourceCardResponse> cards = sourceCards(source);
		if (cards.isEmpty()) {
			return noPayableCardResponse();
		}

		BenefitScoreContext context = new BenefitScoreContext(
			paymentCategory,
			MerchantNameNormalizer.normalize(request.merchantName()),
			request.amount(),
			LocalDateTime.now(clock)
		);
		return cards.stream()
			.map(card -> calculateCard(card, context))
			.filter(candidate -> candidate.performanceScore().hasTarget())
			.sorted(performancePriority())
			.findFirst()
			.map(this::successResponse)
			.orElseGet(() -> fallbackResponse(cards, context));
	}

	private PerformanceRecommendationCandidate calculateCard(
		CardRecommendationSourceCardResponse card,
		BenefitScoreContext context
	) {
		PerformanceTargetScore performanceScore = performanceTargetCalculator.calculate(card, context.amount());
		BenefitScore benefitScore = benefitScoreCalculator.calculate(card, context);
		return new PerformanceRecommendationCandidate(card, context.amount(), performanceScore, benefitScore);
	}

	private PerfSingleRecommendationResponse successResponse(PerformanceRecommendationCandidate candidate) {
		return new PerfSingleRecommendationResponse(
			STRATEGY_TYPE,
			candidate.benefitScore().totalBenefitAmount(),
			List.of(toRecommendedCard(candidate, candidate.benefitScore().warnings())),
			null
		);
	}

	private PerfSingleRecommendationResponse fallbackResponse(
		List<CardRecommendationSourceCardResponse> cards,
		BenefitScoreContext context
	) {
		return cards.stream()
			.filter(card -> Boolean.TRUE.equals(card.isDefault()))
			.findFirst()
			.map(card -> fallbackResponse(card, context))
			.orElseGet(this::defaultMissingResponse);
	}

	private PerfSingleRecommendationResponse fallbackResponse(
		CardRecommendationSourceCardResponse fallbackCard,
		BenefitScoreContext context
	) {
		PerformanceRecommendationCandidate candidate = calculateCard(fallbackCard, context);
		return new PerfSingleRecommendationResponse(
			STRATEGY_TYPE,
			candidate.benefitScore().totalBenefitAmount(),
			List.of(toRecommendedCard(candidate, fallbackWarnings(candidate.benefitScore()))),
			null
		);
	}

	private PerfSingleRecommendationResponse noPayableCardResponse() {
		return new PerfSingleRecommendationResponse(STRATEGY_TYPE, 0L, List.of(), REASON_NO_PAYABLE_CARD);
	}

	private PerfSingleRecommendationResponse defaultMissingResponse() {
		return new PerfSingleRecommendationResponse(STRATEGY_TYPE, 0L, List.of(), REASON_CARD_DEFAULT_MISSING);
	}

	private RecommendedCardResponse toRecommendedCard(
		PerformanceRecommendationCandidate candidate,
		List<String> warnings
	) {
		CardRecommendationSourceCardResponse card = candidate.card();
		BenefitScore benefitScore = candidate.benefitScore();
		PerformanceTargetScore performanceScore = candidate.performanceScore();
		return new RecommendedCardResponse(
			card.cardId(),
			card.cardProductId(),
			card.cardCompany(),
			card.cardName(),
			card.maskedNumber(),
			candidate.amount(),
			benefitScore.discountAmount(),
			benefitScore.cashbackAmount(),
			benefitScore.mileageAmount(),
			benefitScore.totalBenefitAmount(),
			performanceScore.currentPerformanceAmount(),
			performanceScore.targetPerformanceAmount(),
			performanceScore.remainingToTarget(),
			performanceScore.expectedPerformanceAmount(),
			performanceScore.willReachTarget(),
			warnings
		);
	}

	private List<String> fallbackWarnings(BenefitScore benefitScore) {
		Set<String> warnings = new LinkedHashSet<>();
		warnings.add(WARNING_NO_PERFORMANCE_TARGET);
		warnings.addAll(benefitScore.warnings());
		return new ArrayList<>(warnings);
	}

	private Comparator<PerformanceRecommendationCandidate> performancePriority() {
		return Comparator
			.<PerformanceRecommendationCandidate, Long>comparing(
				candidate -> candidate.performanceScore().remainingToTarget(),
				Comparator.nullsLast(Comparator.naturalOrder())
			)
			.thenComparing(
				candidate -> candidate.performanceScore().targetPerformanceAmount(),
				Comparator.nullsLast(Comparator.reverseOrder())
			)
			.thenComparing(Comparator.comparingLong(
				(PerformanceRecommendationCandidate candidate) -> candidate.benefitScore().totalBenefitAmount()
			).reversed())
			.thenComparing(Comparator.comparingLong(
				(PerformanceRecommendationCandidate candidate) -> candidate.benefitScore().discountAmount()
			).reversed())
			.thenComparing(Comparator.comparingLong(
				(PerformanceRecommendationCandidate candidate) -> candidate.benefitScore().cashbackAmount()
			).reversed())
			.thenComparing(Comparator.comparingLong(
				(PerformanceRecommendationCandidate candidate) -> candidate.benefitScore().mileageAmount()
			).reversed())
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private void validateRequest(PerfSingleRecommendationRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("recommendation request is required");
		}
		if (request.userId() == null) {
			throw new IllegalArgumentException("userId is required");
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

	private List<CardRecommendationSourceCardResponse> sourceCards(CardRecommendationSourceResponse source) {
		if (source == null) {
			throw new IllegalStateException("card-service recommendation-source response is required");
		}
		return safeList(source.cards());
	}

	private record PerformanceRecommendationCandidate(
		CardRecommendationSourceCardResponse card,
		long amount,
		PerformanceTargetScore performanceScore,
		BenefitScore benefitScore
	) {
	}
}
