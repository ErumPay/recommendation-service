package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationRequest;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationResponse;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationResponse.RecommendedCardResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScore;
import com.erumpay.recommendation.service.BenefitScoreCalculator.BenefitScoreContext;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BenefitSingleRecommendationService {

	private static final String STRATEGY_TYPE = "BENEFIT_SINGLE";
	private static final String REASON_NO_PAYABLE_CARD = "NO_PAYABLE_CARD";
	private static final String WARNING_NO_APPLICABLE_BENEFIT = "NO_APPLICABLE_BENEFIT";

	private final MerchantCategoryResolverService merchantCategoryResolverService;
	private final CardRecommendationSourceService cardRecommendationSourceService;
	private final BenefitScoreCalculator benefitScoreCalculator;
	private final Clock clock;

	// [be] 이준혁 260526 1440 | 결제 금액 기준 단일 카드 혜택을 계산하고, 적용 혜택이 없으면 주카드 fallback을 반환한다.
	public BenefitSingleRecommendationResponse recommend(BenefitSingleRecommendationRequest request) {
		ServiceCategory paymentCategory = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest(request.merchantName(), request.mccCode())
		).serviceCategory();
		CardRecommendationSourceResponse source = cardRecommendationSourceService.getRecommendationSource(
			request.userId()
		);
		List<CardRecommendationSourceCardResponse> cards = safeList(source.cards());
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
			.filter(candidate -> candidate.totalBenefitAmount() > 0)
			.sorted(benefitPriority())
			.findFirst()
			.map(this::successResponse)
			.orElseGet(() -> fallbackResponse(cards, context.amount()));
	}

	// [be] 이준혁 260526 1440 | 카드 하나에 적용 가능한 모든 혜택을 유형별로 합산한다.
	private CardBenefitCandidate calculateCard(
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
		long amount
	) {
		CardRecommendationSourceCardResponse fallbackCard = cards.stream()
			.filter(card -> Boolean.TRUE.equals(card.isDefault()))
			.findFirst()
			.orElseGet(() -> cards.stream()
				.min(Comparator.comparing(
					CardRecommendationSourceCardResponse::cardId,
					Comparator.nullsLast(Comparator.naturalOrder())
				))
				.orElseThrow());

		return new BenefitSingleRecommendationResponse(
			STRATEGY_TYPE,
			0L,
			List.of(new RecommendedCardResponse(
				fallbackCard.cardId(),
				fallbackCard.cardProductId(),
				fallbackCard.cardCompany(),
				fallbackCard.cardName(),
				fallbackCard.maskedNumber(),
				amount,
				0L,
				0L,
				0L,
				0L,
				List.of(WARNING_NO_APPLICABLE_BENEFIT)
			)),
			null
		);
	}

	private BenefitSingleRecommendationResponse noPayableCardResponse() {
		return new BenefitSingleRecommendationResponse(STRATEGY_TYPE, 0L, List.of(), REASON_NO_PAYABLE_CARD);
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
			.thenComparing(candidate -> !Boolean.TRUE.equals(candidate.card().isDefault()))
			.thenComparing(
				candidate -> candidate.card().cardId(),
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
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
}
