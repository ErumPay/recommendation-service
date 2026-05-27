package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.dto.PerfSplitRecommendationRequest;
import com.erumpay.recommendation.dto.PerfSplitRecommendationResponse;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PerfSplitRecommendationService {

	private static final String STRATEGY_TYPE = "PERF_SPLIT";
	private static final String REASON_NO_PAYABLE_CARD = "NO_PAYABLE_CARD";
	private static final String REASON_CARD_DEFAULT_MISSING = "CARD_DEFAULT_MISSING";
	private static final String WARNING_NO_PERFORMANCE_TARGET = "NO_PERFORMANCE_TARGET";

	private final MerchantCategoryResolverService merchantCategoryResolverService;
	private final CardRecommendationSourceService cardRecommendationSourceService;
	private final BenefitScoreCalculator benefitScoreCalculator;
	private final PerformanceTargetCalculator performanceTargetCalculator;
	private final Clock clock;

	// [be] 이준혁 260528 0732 | 실적 목표 달성 관점에서 단일 후보와 분할 후보를 비교해 더 나은 결과를 반환한다.
	public PerfSplitRecommendationResponse recommend(PerfSplitRecommendationRequest request) {
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
		Optional<PerformanceCardCandidate> singleCandidate = bestSingleCandidate(cards, context);
		if (singleCandidate.isEmpty()) {
			return fallbackResponse(cards, context);
		}

		Optional<PerformanceSplitCandidate> splitCandidate = splitCandidate(cards, context);
		if (splitCandidate.isPresent() && isSplitBetter(splitCandidate.get(), singleCandidate.get())) {
			return splitResponse(splitCandidate.get());
		}
		return singleResponse(singleCandidate.get());
	}

	private Optional<PerformanceCardCandidate> bestSingleCandidate(
		List<CardRecommendationSourceCardResponse> cards,
		BenefitScoreContext context
	) {
		return cards.stream()
			.map(card -> cardCandidate(card, context.amount(), context))
			.filter(candidate -> candidate.performanceScore().hasTarget())
			.sorted(singleCandidatePriority())
			.findFirst();
	}

	// [be] 이준혁 260528 0732 | 남은 결제금액을 가장 가까운 다음 목표 실적 카드부터 순차 배정한다.
	private Optional<PerformanceSplitCandidate> splitCandidate(
		List<CardRecommendationSourceCardResponse> cards,
		BenefitScoreContext context
	) {
		long remainingAmount = context.amount();
		List<PerformanceAllocation> allocations = new ArrayList<>();
		while (remainingAmount > 0) {
			Optional<PerformanceAllocationCandidate> nextCandidate = nextAllocationCandidate(
				cards,
				allocations,
				context,
				remainingAmount
			);
			if (nextCandidate.isEmpty()) {
				break;
			}

			PerformanceAllocationCandidate selected = nextCandidate.get();
			addAllocation(allocations, selected.card(), selected.amount());
			remainingAmount -= selected.amount();
		}

		if (allocations.isEmpty()) {
			return Optional.empty();
		}
		if (remainingAmount > 0) {
			allocations.getFirst().addAmount(remainingAmount);
		}

		List<PerformanceCardCandidate> responseCards = allocations.stream()
			.map(allocation -> cardCandidate(allocation.card(), allocation.amount(), context))
			.sorted(responseCardPriority())
			.toList();
		return Optional.of(new PerformanceSplitCandidate(
			responseCards,
			recommendationSummary(responseCards)
		));
	}

	private Optional<PerformanceAllocationCandidate> nextAllocationCandidate(
		List<CardRecommendationSourceCardResponse> cards,
		List<PerformanceAllocation> allocations,
		BenefitScoreContext context,
		long remainingAmount
	) {
		return cards.stream()
			.map(card -> allocationCandidate(card, allocations, context, remainingAmount))
			.flatMap(Optional::stream)
			.sorted(allocationCandidatePriority())
			.findFirst();
	}

	private Optional<PerformanceAllocationCandidate> allocationCandidate(
		CardRecommendationSourceCardResponse card,
		List<PerformanceAllocation> allocations,
		BenefitScoreContext context,
		long remainingAmount
	) {
		long allocatedAmount = allocatedAmount(allocations, card);
		PerformanceTargetScore score = performanceTargetCalculator.calculate(card, allocatedAmount + remainingAmount);
		if (score.remainingToTarget() == null || score.remainingToTarget() <= allocatedAmount) {
			return Optional.empty();
		}

		long remainingAfterAllocated = score.remainingToTarget() - allocatedAmount;
		long amount = Math.min(remainingAmount, remainingAfterAllocated);
		BenefitScore benefitScore = benefitScoreCalculator.calculate(card, benefitContext(context, amount));
		return Optional.of(new PerformanceAllocationCandidate(
			card,
			amount,
			score,
			benefitScore,
			remainingAfterAllocated
		));
	}

	private void addAllocation(
		List<PerformanceAllocation> allocations,
		CardRecommendationSourceCardResponse card,
		long amount
	) {
		findAllocation(allocations, card)
			.ifPresentOrElse(
				allocation -> allocation.addAmount(amount),
				() -> allocations.add(new PerformanceAllocation(card, amount))
			);
	}

	private Optional<PerformanceAllocation> findAllocation(
		List<PerformanceAllocation> allocations,
		CardRecommendationSourceCardResponse card
	) {
		return allocations.stream()
			.filter(allocation -> Objects.equals(allocation.card().cardId(), card.cardId()))
			.findFirst();
	}

	private long allocatedAmount(
		List<PerformanceAllocation> allocations,
		CardRecommendationSourceCardResponse card
	) {
		return allocations.stream()
			.filter(allocation -> Objects.equals(allocation.card().cardId(), card.cardId()))
			.mapToLong(PerformanceAllocation::amount)
			.sum();
	}

	private PerformanceCardCandidate cardCandidate(
		CardRecommendationSourceCardResponse card,
		long amount,
		BenefitScoreContext context
	) {
		PerformanceTargetScore performanceScore = performanceTargetCalculator.calculate(card, amount);
		BenefitScore benefitScore = benefitScoreCalculator.calculate(card, benefitContext(context, amount));
		return new PerformanceCardCandidate(card, amount, performanceScore, benefitScore);
	}

	private BenefitScoreContext benefitContext(BenefitScoreContext context, long amount) {
		return new BenefitScoreContext(
			context.paymentCategory(),
			context.normalizedMerchantName(),
			amount,
			context.calculatedAt()
		);
	}

	private boolean isSplitBetter(
		PerformanceSplitCandidate splitCandidate,
		PerformanceCardCandidate singleCandidate
	) {
		RecommendationSummary split = splitCandidate.summary();
		RecommendationSummary single = recommendationSummary(List.of(singleCandidate));
		int reachedCountCompare = Integer.compare(split.reachedTargetCount(), single.reachedTargetCount());
		if (reachedCountCompare != 0) {
			return reachedCountCompare > 0;
		}

		int reachedTargetAmountCompare = Long.compare(
			split.reachedTargetAmountSum(),
			single.reachedTargetAmountSum()
		);
		if (reachedTargetAmountCompare != 0) {
			return reachedTargetAmountCompare > 0;
		}

		int contributionCompare = Long.compare(
			split.performanceContributionAmount(),
			single.performanceContributionAmount()
		);
		if (contributionCompare != 0) {
			return contributionCompare > 0;
		}

		int benefitCompare = Long.compare(split.totalBenefitAmount(), single.totalBenefitAmount());
		if (benefitCompare != 0) {
			return benefitCompare > 0;
		}
		return split.cardCount() < single.cardCount();
	}

	private RecommendationSummary recommendationSummary(List<PerformanceCardCandidate> candidates) {
		int reachedTargetCount = (int) candidates.stream()
			.filter(candidate -> Boolean.TRUE.equals(candidate.performanceScore().willReachTarget()))
			.count();
		long reachedTargetAmountSum = candidates.stream()
			.filter(candidate -> Boolean.TRUE.equals(candidate.performanceScore().willReachTarget()))
			.map(PerformanceCardCandidate::performanceScore)
			.map(PerformanceTargetScore::targetPerformanceAmount)
			.filter(Objects::nonNull)
			.mapToLong(Long::longValue)
			.sum();
		long contributionAmount = candidates.stream()
			.mapToLong(this::performanceContributionAmount)
			.sum();
		long totalBenefitAmount = candidates.stream()
			.mapToLong(candidate -> candidate.benefitScore().totalBenefitAmount())
			.sum();
		return new RecommendationSummary(
			reachedTargetCount,
			reachedTargetAmountSum,
			contributionAmount,
			totalBenefitAmount,
			candidates.size()
		);
	}

	private long performanceContributionAmount(PerformanceCardCandidate candidate) {
		Long remainingToTarget = candidate.performanceScore().remainingToTarget();
		if (remainingToTarget == null) {
			return 0L;
		}
		return Math.min(candidate.amount(), remainingToTarget);
	}

	private PerfSplitRecommendationResponse splitResponse(PerformanceSplitCandidate candidate) {
		return new PerfSplitRecommendationResponse(
			STRATEGY_TYPE,
			candidate.summary().totalBenefitAmount(),
			candidate.cards().stream()
				.map(this::toRecommendedCard)
				.toList(),
			null
		);
	}

	private PerfSplitRecommendationResponse singleResponse(PerformanceCardCandidate candidate) {
		return new PerfSplitRecommendationResponse(
			STRATEGY_TYPE,
			candidate.benefitScore().totalBenefitAmount(),
			List.of(toRecommendedCard(candidate)),
			null
		);
	}

	private PerfSplitRecommendationResponse fallbackResponse(
		List<CardRecommendationSourceCardResponse> cards,
		BenefitScoreContext context
	) {
		return cards.stream()
			.filter(card -> Boolean.TRUE.equals(card.isDefault()))
			.findFirst()
			.map(card -> fallbackResponse(card, context))
			.orElseGet(this::defaultMissingResponse);
	}

	private PerfSplitRecommendationResponse fallbackResponse(
		CardRecommendationSourceCardResponse fallbackCard,
		BenefitScoreContext context
	) {
		PerformanceCardCandidate candidate = cardCandidate(fallbackCard, context.amount(), context);
		return new PerfSplitRecommendationResponse(
			STRATEGY_TYPE,
			candidate.benefitScore().totalBenefitAmount(),
			List.of(toRecommendedCard(candidate, fallbackWarnings(candidate.benefitScore()))),
			null
		);
	}

	private PerfSplitRecommendationResponse noPayableCardResponse() {
		return new PerfSplitRecommendationResponse(STRATEGY_TYPE, 0L, List.of(), REASON_NO_PAYABLE_CARD);
	}

	private PerfSplitRecommendationResponse defaultMissingResponse() {
		return new PerfSplitRecommendationResponse(STRATEGY_TYPE, 0L, List.of(), REASON_CARD_DEFAULT_MISSING);
	}

	private RecommendedCardResponse toRecommendedCard(PerformanceCardCandidate candidate) {
		return toRecommendedCard(candidate, candidate.benefitScore().warnings());
	}

	private RecommendedCardResponse toRecommendedCard(
		PerformanceCardCandidate candidate,
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

	private Comparator<PerformanceCardCandidate> singleCandidatePriority() {
		return Comparator
			.<PerformanceCardCandidate, Long>comparing(
				candidate -> candidate.performanceScore().remainingToTarget(),
				Comparator.nullsLast(Comparator.naturalOrder())
			)
			.thenComparing(
				candidate -> candidate.performanceScore().targetPerformanceAmount(),
				Comparator.nullsLast(Comparator.reverseOrder())
			)
			.thenComparing(benefitPriority())
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private Comparator<PerformanceAllocationCandidate> allocationCandidatePriority() {
		return Comparator
			.comparingLong(PerformanceAllocationCandidate::remainingAfterAllocated)
			.thenComparing(
				candidate -> candidate.performanceScore().targetPerformanceAmount(),
				Comparator.nullsLast(Comparator.reverseOrder())
			)
			.thenComparing(benefitPriority())
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private Comparator<PerformanceCardCandidate> responseCardPriority() {
		return Comparator
			.<PerformanceCardCandidate, Boolean>comparing(
				candidate -> !Boolean.TRUE.equals(candidate.performanceScore().willReachTarget())
			)
			.thenComparing(
				candidate -> candidate.performanceScore().targetPerformanceAmount(),
				Comparator.nullsLast(Comparator.reverseOrder())
			)
			.thenComparing(Comparator.comparingLong(PerformanceCardCandidate::amount).reversed())
			.thenComparing(benefitPriority())
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private <T extends BenefitCalculatedCandidate> Comparator<T> benefitPriority() {
		return Comparator
			.<T>comparingLong(candidate -> candidate.benefitScore().totalBenefitAmount())
			.reversed()
			.thenComparing(Comparator.comparingLong(
				(T candidate) -> candidate.benefitScore().discountAmount()
			).reversed())
			.thenComparing(Comparator.comparingLong(
				(T candidate) -> candidate.benefitScore().cashbackAmount()
			).reversed())
			.thenComparing(Comparator.comparingLong(
				(T candidate) -> candidate.benefitScore().mileageAmount()
			).reversed());
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private List<CardRecommendationSourceCardResponse> sourceCards(CardRecommendationSourceResponse source) {
		if (source == null) {
			return List.of();
		}
		return safeList(source.cards());
	}

	private void validateRequest(PerfSplitRecommendationRequest request) {
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

	private interface BenefitCalculatedCandidate {
		BenefitScore benefitScore();
	}

	private record PerformanceCardCandidate(
		CardRecommendationSourceCardResponse card,
		long amount,
		PerformanceTargetScore performanceScore,
		BenefitScore benefitScore
	) implements BenefitCalculatedCandidate {
	}

	private record PerformanceAllocationCandidate(
		CardRecommendationSourceCardResponse card,
		long amount,
		PerformanceTargetScore performanceScore,
		BenefitScore benefitScore,
		long remainingAfterAllocated
	) implements BenefitCalculatedCandidate {
	}

	private record PerformanceSplitCandidate(
		List<PerformanceCardCandidate> cards,
		RecommendationSummary summary
	) {
	}

	private record RecommendationSummary(
		int reachedTargetCount,
		long reachedTargetAmountSum,
		long performanceContributionAmount,
		long totalBenefitAmount,
		int cardCount
	) {
	}

	private static final class PerformanceAllocation {

		private final CardRecommendationSourceCardResponse card;
		private long amount;

		private PerformanceAllocation(CardRecommendationSourceCardResponse card, long amount) {
			this.card = card;
			this.amount = amount;
		}

		void addAmount(long additionalAmount) {
			amount += additionalAmount;
		}

		CardRecommendationSourceCardResponse card() {
			return card;
		}

		long amount() {
			return amount;
		}
	}
}
