package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.AppliedBenefitResponse;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationRequest;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.dto.RecommendedCardResponse;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScore;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScoreContext;
import com.erumpay.recommendation.service.PerformanceTargetCalculator.PerformanceTargetScore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BenefitSingleRecommendationService {

	private static final String STRATEGY_TYPE = "BENEFIT_SINGLE";
	private static final String REASON_NO_PAYABLE_CARD = "NO_PAYABLE_CARD";
	private static final String REASON_CARD_DEFAULT_MISSING = "CARD_DEFAULT_MISSING";
	private static final String WARNING_NO_APPLICABLE_BENEFIT = "NO_APPLICABLE_BENEFIT";

	private final MerchantCategoryResolverService merchantCategoryResolverService;
	private final CardRecommendationSourceService cardRecommendationSourceService;
	private final BenefitScoreCalculator benefitScoreCalculator;
	private final PerformanceTargetCalculator performanceTargetCalculator;
	private final Clock clock;

	// [be] 이준혁 260526 1440 | 결제 금액 기준 단일 카드 혜택을 계산하고, 적용 혜택이 없으면 주카드 fallback을 반환한다.
	public BenefitSingleRecommendationResponse recommend(BenefitSingleRecommendationRequest request) {
		validateRequest(request);
		ServiceCategory paymentCategory = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest(request.merchantName(), request.mccCode())
		).serviceCategory();
		CardRecommendationSourceResponse source = cardRecommendationSourceService.getRecommendationSource(
			request.userId()
		);
		BenefitScoreContext context = new BenefitScoreContext(
			paymentCategory,
			MerchantNameNormalizer.normalize(request.merchantName()),
			request.amount(),
			LocalDateTime.now(clock)
		);
		return calculate(source, context);
	}

	// [be] 이준혁 260528 0732 | 통합 추천 계산에서 이미 조회한 추천 소스를 재사용해 BENEFIT_SINGLE 결과를 계산한다.
	BenefitSingleRecommendationResponse calculate(
		CardRecommendationSourceResponse source,
		BenefitScoreContext context
	) {
		List<CardRecommendationSourceCardResponse> cards = sourceCards(source);
		if (cards.isEmpty()) {
			return noPayableCardResponse();
		}

		return cards.stream()
			.map(card -> calculateCard(card, context))
			.filter(candidate -> candidate.totalBenefitAmount() > 0)
			.sorted(benefitPriority())
			.findFirst()
			.map(this::successResponse)
			.orElseGet(() -> fallbackResponse(cards, context));
	}

	// [be] 이준혁 260527 2306 | 카드 하나의 최고 혜택 1개와 실적 점수를 함께 계산한다.
	private CardBenefitCandidate calculateCard(
		CardRecommendationSourceCardResponse card,
		BenefitScoreContext context
	) {
		BenefitScore score = benefitScoreCalculator.calculate(card, context);
		PerformanceTargetScore performanceScore = performanceTargetCalculator.calculate(card, context.amount());

		return new CardBenefitCandidate(
			card,
			context.amount(),
			score.discountAmount(),
			score.cashbackAmount(),
			score.mileageAmount(),
			score.totalBenefitAmount(),
			appliedBenefit(score),
			performanceScore.currentPerformanceAmount(),
			performanceScore.targetPerformanceAmount(),
			performanceScore.remainingToTarget(),
			performanceScore.expectedPerformanceAmount(),
			performanceScore.willReachTarget(),
			score.warnings()
		);
	}

	private BenefitSingleRecommendationResponse successResponse(CardBenefitCandidate candidate) {
		return new BenefitSingleRecommendationResponse(
			STRATEGY_TYPE,
			candidate.totalBenefitAmount(),
			List.of(toRecommendedCard(candidate)),
			null
		);
	}

	private BenefitSingleRecommendationResponse fallbackResponse(
		List<CardRecommendationSourceCardResponse> cards,
		BenefitScoreContext context
	) {
		return cards.stream()
			.filter(card -> Boolean.TRUE.equals(card.isDefault()))
			.findFirst()
			.map(card -> fallbackResponse(card, context))
			.orElseGet(this::defaultMissingResponse);
	}

	private BenefitSingleRecommendationResponse fallbackResponse(
		CardRecommendationSourceCardResponse fallbackCard,
		BenefitScoreContext context
	) {
		BenefitScore benefitScore = benefitScoreCalculator.calculate(fallbackCard, context);
		PerformanceTargetScore performanceScore = performanceTargetCalculator.calculate(fallbackCard, context.amount());

		return new BenefitSingleRecommendationResponse(
			STRATEGY_TYPE,
			benefitScore.totalBenefitAmount(),
			List.of(new RecommendedCardResponse(
				fallbackCard.cardId(),
				fallbackCard.cardProductId(),
				fallbackCard.cardCompany(),
				fallbackCard.cardName(),
				fallbackCard.maskedNumber(),
				context.amount(),
				benefitScore.discountAmount(),
				benefitScore.cashbackAmount(),
				benefitScore.mileageAmount(),
				benefitScore.totalBenefitAmount(),
				appliedBenefit(benefitScore),
				performanceScore.currentPerformanceAmount(),
				performanceScore.targetPerformanceAmount(),
				performanceScore.remainingToTarget(),
				performanceScore.expectedPerformanceAmount(),
				performanceScore.willReachTarget(),
				List.of(WARNING_NO_APPLICABLE_BENEFIT)
			)),
			null
		);
	}

	private BenefitSingleRecommendationResponse noPayableCardResponse() {
		return new BenefitSingleRecommendationResponse(STRATEGY_TYPE, 0L, List.of(), REASON_NO_PAYABLE_CARD);
	}

	private BenefitSingleRecommendationResponse defaultMissingResponse() {
		return new BenefitSingleRecommendationResponse(STRATEGY_TYPE, 0L, List.of(), REASON_CARD_DEFAULT_MISSING);
	}

	private RecommendedCardResponse toRecommendedCard(CardBenefitCandidate candidate) {
		CardRecommendationSourceCardResponse card = candidate.card();
		return new RecommendedCardResponse(
			card.cardId(),
			card.cardProductId(),
			card.cardCompany(),
			card.cardName(),
			card.maskedNumber(),
			candidate.amount(),
			candidate.discountAmount(),
			candidate.cashbackAmount(),
			candidate.mileageAmount(),
			candidate.totalBenefitAmount(),
			candidate.appliedBenefit(),
			candidate.currentPerformanceAmount(),
			candidate.targetPerformanceAmount(),
			candidate.remainingToTarget(),
			candidate.expectedPerformanceAmount(),
			candidate.willReachTarget(),
			candidate.warnings()
		);
	}

	private Comparator<CardBenefitCandidate> benefitPriority() {
		return Comparator
			.comparingLong(CardBenefitCandidate::totalBenefitAmount)
			.reversed()
			.thenComparing(Comparator.comparingLong(CardBenefitCandidate::discountAmount).reversed())
			.thenComparing(Comparator.comparingLong(CardBenefitCandidate::cashbackAmount).reversed())
			.thenComparing(Comparator.comparingLong(CardBenefitCandidate::mileageAmount).reversed())
			.thenComparing(
				CardBenefitCandidate::remainingToTarget,
				Comparator.nullsLast(Comparator.naturalOrder())
			)
			.thenComparing(
				CardBenefitCandidate::targetPerformanceAmount,
				Comparator.nullsLast(Comparator.reverseOrder())
			)
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
		);
	}

	private AppliedBenefitResponse appliedBenefit(BenefitScore score) {
		return AppliedBenefitResponse.of(
			score.selectedBenefitId(),
			score.selectedTierId(),
			score.benefitAmount()
		);
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private void validateRequest(BenefitSingleRecommendationRequest request) {
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
			return List.of();
		}
		return safeList(source.cards());
	}

	private record CardBenefitCandidate(
		CardRecommendationSourceCardResponse card,
		long amount,
		long discountAmount,
		long cashbackAmount,
		long mileageAmount,
		long totalBenefitAmount,
		AppliedBenefitResponse appliedBenefit,
		Long currentPerformanceAmount,
		Long targetPerformanceAmount,
		Long remainingToTarget,
		Long expectedPerformanceAmount,
		Boolean willReachTarget,
		List<String> warnings
	) {
	}
}
