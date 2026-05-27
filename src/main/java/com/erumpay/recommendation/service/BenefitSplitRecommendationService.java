package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.BenefitSplitRecommendationRequest;
import com.erumpay.recommendation.dto.BenefitSplitRecommendationResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.dto.RecommendedCardResponse;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScore;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScoreContext;
import com.erumpay.recommendation.service.BenefitScoreCalculator.SplitBenefitScore;
import com.erumpay.recommendation.service.PerformanceTargetCalculator.PerformanceTargetScore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BenefitSplitRecommendationService {

	private static final String STRATEGY_TYPE = "BENEFIT_SPLIT";
	private static final String REASON_NO_PAYABLE_CARD = "NO_PAYABLE_CARD";
	private static final String REASON_CARD_DEFAULT_MISSING = "CARD_DEFAULT_MISSING";
	private static final String WARNING_NO_APPLICABLE_BENEFIT = "NO_APPLICABLE_BENEFIT";

	private final MerchantCategoryResolverService merchantCategoryResolverService;
	private final CardRecommendationSourceService cardRecommendationSourceService;
	private final BenefitScoreCalculator benefitScoreCalculator;
	private final PerformanceTargetCalculator performanceTargetCalculator;
	private final Clock clock;

	// [be] 이준혁 260527 2306 | 혜택 최대화를 위해 단일 카드 후보와 greedy 분할 후보를 비교해 더 큰 결과를 반환한다.
	public BenefitSplitRecommendationResponse recommend(BenefitSplitRecommendationRequest request) {
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
		Optional<CardBenefitCandidate> singleCandidate = bestSingleCandidate(cards, context);
		Optional<SplitRecommendationCandidate> splitCandidate = splitCandidate(cards, context);
		if (splitCandidate.isPresent()
			&& splitCandidate.get().totalBenefitAmount() > singleCandidate
				.map(CardBenefitCandidate::totalBenefitAmount)
				.orElse(0L)) {
			return splitResponse(splitCandidate.get());
		}
		return singleCandidate
			.map(this::singleResponse)
			.orElseGet(() -> fallbackResponse(cards, context));
	}

	private Optional<CardBenefitCandidate> bestSingleCandidate(
		List<CardRecommendationSourceCardResponse> cards,
		BenefitScoreContext context
	) {
		return cards.stream()
			.map(card -> singleCardCandidate(card, context))
			.filter(candidate -> candidate.totalBenefitAmount() > 0)
			.sorted(benefitPriority())
			.findFirst();
	}

	// [be] 이준혁 260527 2306 | 남은 결제금액마다 후보를 재평가하고 같은 카드는 한 번만 선택한다.
	private Optional<SplitRecommendationCandidate> splitCandidate(
		List<CardRecommendationSourceCardResponse> cards,
		BenefitScoreContext context
	) {
		long remainingAmount = context.amount();
		Set<Long> selectedCardIds = new HashSet<>();
		List<SplitAllocation> allocations = new ArrayList<>();
		while (remainingAmount > 0) {
			Optional<SplitCandidate> nextCandidate = nextSplitCandidate(cards, context, remainingAmount, selectedCardIds);
			if (nextCandidate.isEmpty()) {
				break;
			}

			SplitCandidate selected = nextCandidate.get();
			SplitAllocation allocation = SplitAllocation.from(selected.card(), selected.score());
			allocations.add(allocation);
			selectedCardIds.add(selected.card().cardId());
			remainingAmount -= selected.score().candidateAmount();
		}

		if (allocations.isEmpty()) {
			return Optional.empty();
		}

		long totalBenefitAmount = allocations.stream()
			.mapToLong(SplitAllocation::totalBenefitAmount)
			.sum();
		if (remainingAmount > 0) {
			assignRemainder(cards, allocations, remainingAmount);
		}
		List<CardBenefitCandidate> responseCards = allocations.stream()
			.map(SplitAllocation::toCandidate)
			.sorted(responseCardPriority())
			.toList();
		return Optional.of(new SplitRecommendationCandidate(responseCards, totalBenefitAmount));
	}

	private Optional<SplitCandidate> nextSplitCandidate(
		List<CardRecommendationSourceCardResponse> cards,
		BenefitScoreContext context,
		long remainingAmount,
		Set<Long> selectedCardIds
	) {
		return cards.stream()
			.filter(card -> !selectedCardIds.contains(card.cardId()))
			.flatMap(card -> benefitScoreCalculator.calculateSplitCandidates(card, context, remainingAmount)
				.stream()
				.map(score -> new SplitCandidate(card, score)))
			.sorted(splitCandidatePriority())
			.findFirst();
	}

	private void assignRemainder(
		List<CardRecommendationSourceCardResponse> cards,
		List<SplitAllocation> allocations,
		long remainingAmount
	) {
		CardRecommendationSourceCardResponse targetCard = performanceRemainderCard(cards, allocations, remainingAmount)
			.orElseGet(() -> allocations.getFirst().card());
		findAllocation(allocations, targetCard)
			.ifPresentOrElse(
				allocation -> allocation.addAmount(remainingAmount),
				() -> allocations.add(SplitAllocation.performanceOnly(targetCard, remainingAmount))
			);
	}

	private Optional<CardRecommendationSourceCardResponse> performanceRemainderCard(
		List<CardRecommendationSourceCardResponse> cards,
		List<SplitAllocation> allocations,
		long remainingAmount
	) {
		return cards.stream()
			.map(card -> performanceRemainderCandidate(card, allocations, remainingAmount))
			.flatMap(Optional::stream)
			.sorted(performanceRemainderPriority())
			.map(PerformanceRemainderCandidate::card)
			.findFirst();
	}

	private Optional<PerformanceRemainderCandidate> performanceRemainderCandidate(
		CardRecommendationSourceCardResponse card,
		List<SplitAllocation> allocations,
		long remainingAmount
	) {
		long allocatedAmount = allocatedAmount(allocations, card);
		PerformanceTargetScore score = performanceTargetCalculator.calculate(card, allocatedAmount + remainingAmount);
		if (score.remainingToTarget() == null || score.remainingToTarget() <= allocatedAmount) {
			return Optional.empty();
		}
		return Optional.of(new PerformanceRemainderCandidate(
			card,
			score,
			score.remainingToTarget() - allocatedAmount
		));
	}

	private long allocatedAmount(
		List<SplitAllocation> allocations,
		CardRecommendationSourceCardResponse card
	) {
		return allocations.stream()
			.filter(allocation -> Objects.equals(allocation.card().cardId(), card.cardId()))
			.mapToLong(SplitAllocation::amount)
			.sum();
	}

	private Optional<SplitAllocation> findAllocation(
		List<SplitAllocation> allocations,
		CardRecommendationSourceCardResponse card
	) {
		return allocations.stream()
			.filter(allocation -> Objects.equals(allocation.card().cardId(), card.cardId()))
			.findFirst();
	}

	private CardBenefitCandidate singleCardCandidate(
		CardRecommendationSourceCardResponse card,
		BenefitScoreContext context
	) {
		BenefitScore score = benefitScoreCalculator.calculate(card, context);
		return new CardBenefitCandidate(
			card,
			context.amount(),
			score.discountAmount(),
			score.cashbackAmount(),
			score.mileageAmount(),
			score.totalBenefitAmount(),
			score.warnings()
		);
	}

	private BenefitSplitRecommendationResponse splitResponse(SplitRecommendationCandidate candidate) {
		return new BenefitSplitRecommendationResponse(
			STRATEGY_TYPE,
			candidate.totalBenefitAmount(),
			candidate.cards().stream()
				.map(this::toRecommendedCard)
				.toList(),
			null
		);
	}

	private BenefitSplitRecommendationResponse singleResponse(CardBenefitCandidate candidate) {
		return new BenefitSplitRecommendationResponse(
			STRATEGY_TYPE,
			candidate.totalBenefitAmount(),
			List.of(toRecommendedCard(candidate)),
			null
		);
	}

	private BenefitSplitRecommendationResponse fallbackResponse(
		List<CardRecommendationSourceCardResponse> cards,
		BenefitScoreContext context
	) {
		return cards.stream()
			.filter(card -> Boolean.TRUE.equals(card.isDefault()))
			.findFirst()
			.map(card -> fallbackResponse(card, context))
			.orElseGet(this::defaultMissingResponse);
	}

	private BenefitSplitRecommendationResponse fallbackResponse(
		CardRecommendationSourceCardResponse fallbackCard,
		BenefitScoreContext context
	) {
		BenefitScore benefitScore = benefitScoreCalculator.calculate(fallbackCard, context);
		CardBenefitCandidate candidate = new CardBenefitCandidate(
			fallbackCard,
			context.amount(),
			benefitScore.discountAmount(),
			benefitScore.cashbackAmount(),
			benefitScore.mileageAmount(),
			benefitScore.totalBenefitAmount(),
			List.of(WARNING_NO_APPLICABLE_BENEFIT)
		);
		return new BenefitSplitRecommendationResponse(
			STRATEGY_TYPE,
			benefitScore.totalBenefitAmount(),
			List.of(toRecommendedCard(candidate)),
			null
		);
	}

	private BenefitSplitRecommendationResponse noPayableCardResponse() {
		return new BenefitSplitRecommendationResponse(STRATEGY_TYPE, 0L, List.of(), REASON_NO_PAYABLE_CARD);
	}

	private BenefitSplitRecommendationResponse defaultMissingResponse() {
		return new BenefitSplitRecommendationResponse(STRATEGY_TYPE, 0L, List.of(), REASON_CARD_DEFAULT_MISSING);
	}

	private RecommendedCardResponse toRecommendedCard(CardBenefitCandidate candidate) {
		CardRecommendationSourceCardResponse card = candidate.card();
		PerformanceTargetScore performanceScore = performanceTargetCalculator.calculate(card, candidate.amount());
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
			performanceScore.currentPerformanceAmount(),
			performanceScore.targetPerformanceAmount(),
			performanceScore.remainingToTarget(),
			performanceScore.expectedPerformanceAmount(),
			performanceScore.willReachTarget(),
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
				candidate -> performanceTargetCalculator.calculate(candidate.card(), candidate.amount()).remainingToTarget(),
				Comparator.nullsLast(Comparator.naturalOrder())
			)
			.thenComparing(
				candidate -> performanceTargetCalculator.calculate(candidate.card(), candidate.amount()).targetPerformanceAmount(),
				Comparator.nullsLast(Comparator.reverseOrder())
			)
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private Comparator<SplitCandidate> splitCandidatePriority() {
		return Comparator
			.<SplitCandidate, BigDecimal>comparing(candidate -> candidate.score().benefitEfficiency())
			.reversed()
			.thenComparing(Comparator.comparingLong(
				(SplitCandidate candidate) -> candidate.score().benefitAmount()
			).reversed())
			.thenComparingLong(candidate -> candidate.score().candidateAmount())
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			)
			.thenComparing(
				candidate -> candidate.score().selectedBenefitId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private Comparator<PerformanceRemainderCandidate> performanceRemainderPriority() {
		return Comparator
			.comparingLong(PerformanceRemainderCandidate::remainingAfterAllocated)
			.thenComparing(
				candidate -> candidate.score().targetPerformanceAmount(),
				Comparator.nullsLast(Comparator.reverseOrder())
			)
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private Comparator<CardBenefitCandidate> responseCardPriority() {
		return Comparator
			.comparingLong(CardBenefitCandidate::amount)
			.reversed()
			.thenComparing(Comparator.comparingLong(CardBenefitCandidate::totalBenefitAmount).reversed())
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private List<CardRecommendationSourceCardResponse> sourceCards(CardRecommendationSourceResponse source) {
		return safeList(source.cards());
	}

	private void validateRequest(BenefitSplitRecommendationRequest request) {
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

	private record CardBenefitCandidate(
		CardRecommendationSourceCardResponse card,
		long amount,
		long discountAmount,
		long cashbackAmount,
		long mileageAmount,
		long totalBenefitAmount,
		List<String> warnings
	) {
	}

	private record SplitCandidate(
		CardRecommendationSourceCardResponse card,
		SplitBenefitScore score
	) {
	}

	private record PerformanceRemainderCandidate(
		CardRecommendationSourceCardResponse card,
		PerformanceTargetScore score,
		long remainingAfterAllocated
	) {
	}

	private record SplitRecommendationCandidate(
		List<CardBenefitCandidate> cards,
		long totalBenefitAmount
	) {
	}

	private static final class SplitAllocation {

		private final CardRecommendationSourceCardResponse card;
		private long amount;
		private final long discountAmount;
		private final long cashbackAmount;
		private final long mileageAmount;
		private final long totalBenefitAmount;
		private final List<String> warnings;

		private SplitAllocation(
			CardRecommendationSourceCardResponse card,
			long amount,
			long discountAmount,
			long cashbackAmount,
			long mileageAmount,
			long totalBenefitAmount,
			List<String> warnings
		) {
			this.card = card;
			this.amount = amount;
			this.discountAmount = discountAmount;
			this.cashbackAmount = cashbackAmount;
			this.mileageAmount = mileageAmount;
			this.totalBenefitAmount = totalBenefitAmount;
			this.warnings = warnings;
		}

		static SplitAllocation from(CardRecommendationSourceCardResponse card, SplitBenefitScore score) {
			long discountAmount = "DISCOUNT".equals(score.benefitType()) ? score.benefitAmount() : 0L;
			long cashbackAmount = "CASHBACK".equals(score.benefitType()) ? score.benefitAmount() : 0L;
			long mileageAmount = "MILEAGE".equals(score.benefitType()) ? score.benefitAmount() : 0L;
			return new SplitAllocation(
				card,
				score.candidateAmount(),
				discountAmount,
				cashbackAmount,
				mileageAmount,
				score.benefitAmount(),
				score.warnings()
			);
		}

		static SplitAllocation performanceOnly(CardRecommendationSourceCardResponse card, long amount) {
			return new SplitAllocation(card, amount, 0L, 0L, 0L, 0L, List.of());
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

		long totalBenefitAmount() {
			return totalBenefitAmount;
		}

		CardBenefitCandidate toCandidate() {
			return new CardBenefitCandidate(
				card,
				amount,
				discountAmount,
				cashbackAmount,
				mileageAmount,
				totalBenefitAmount,
				warnings
			);
		}
	}
}
