package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationRequest;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationResponse;
import com.erumpay.recommendation.dto.BenefitSingleRecommendationResponse.RecommendedCardResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitUsageResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BenefitSingleRecommendationService {

	private static final String STRATEGY_TYPE = "BENEFIT_SINGLE";
	private static final String REASON_NO_PAYABLE_CARD = "NO_PAYABLE_CARD";
	private static final String WARNING_NO_APPLICABLE_BENEFIT = "NO_APPLICABLE_BENEFIT";
	private static final String WARNING_SHARED_LIMIT_NOT_APPLIED = "SHARED_LIMIT_NOT_APPLIED";
	private static final String WARNING_TIME_END_BOUNDARY_NEAR = "TIME_END_BOUNDARY_NEAR";
	private static final String WARNING_DAY_BOUNDARY_NEAR = "DAY_BOUNDARY_NEAR";
	private static final Duration BOUNDARY_WARNING_THRESHOLD = Duration.ofMinutes(10);
	private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

	private final MerchantCategoryResolverService merchantCategoryResolverService;
	private final CardRecommendationSourceService cardRecommendationSourceService;
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

		CalculationContext context = new CalculationContext(
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
		CalculationContext context
	) {
		BenefitAmounts amounts = new BenefitAmounts();
		Set<String> warnings = new LinkedHashSet<>();

		for (CardBenefitResponse benefit : safeList(card.benefits())) {
			calculateBenefit(benefit, card, context)
				.ifPresent(calculation -> {
					amounts.add(calculation.benefitType(), calculation.amount());
					warnings.addAll(calculation.warnings());
				});
		}

		return new CardBenefitCandidate(
			card,
			context.amount(),
			amounts.discountAmount(),
			amounts.cashbackAmount(),
			amounts.mileageAmount(),
			amounts.totalBenefitAmount(),
			new ArrayList<>(warnings)
		);
	}

	// [be] 이준혁 260526 1440 | category, brand, 시간, 실적 tier, 한도를 모두 통과한 혜택만 금액으로 환산한다.
	private Optional<BenefitCalculation> calculateBenefit(
		CardBenefitResponse benefit,
		CardRecommendationSourceCardResponse card,
		CalculationContext context
	) {
		if (!matchesCategory(benefit, context.paymentCategory())
			|| !matchesBrand(benefit, context.normalizedMerchantName())
			|| !matchesTime(benefit, context.calculatedAt())
			|| !matchesDay(benefit, context.calculatedAt())
			|| !matchesMinAmount(benefit, context.amount())) {
			return Optional.empty();
		}

		Optional<CardBenefitTierResponse> tier = selectApplicableTier(benefit, card.performanceAmount());
		if (tier.isEmpty()) {
			return Optional.empty();
		}

		CardBenefitTierResponse selectedTier = tier.get();
		long benefitAmount = calculateBaseBenefitAmount(context.amount(), selectedTier);
		if (benefitAmount <= 0) {
			return Optional.empty();
		}

		benefitAmount = applyAmountLimits(benefitAmount, selectedTier, benefit.usage());
		if (isCountLimitExhausted(selectedTier, benefit.usage()) || benefitAmount <= 0) {
			return Optional.empty();
		}

		return Optional.of(new BenefitCalculation(
			benefit.benefitType(),
			benefitAmount,
			warnings(benefit, selectedTier, context.calculatedAt())
		));
	}

	// [be] 이준혁 260526 1440 | 전월실적 조건은 min 이상, max 미만인 tier 중 가장 높은 min 구간을 선택한다.
	private Optional<CardBenefitTierResponse> selectApplicableTier(CardBenefitResponse benefit, Long performanceAmount) {
		long performance = defaultLong(performanceAmount);
		return safeList(benefit.tiers()).stream()
			.filter(tier -> performance >= defaultLong(tier.minPrevMonthUsage()))
			.filter(tier -> tier.maxPrevMonthUsage() == null || performance < tier.maxPrevMonthUsage())
			.max(Comparator.comparingLong(tier -> defaultLong(tier.minPrevMonthUsage())));
	}

	private boolean matchesCategory(CardBenefitResponse benefit, ServiceCategory paymentCategory) {
		ServiceCategory benefitCategory = toServiceCategory(benefit.serviceCategory());
		if (benefitCategory == null) {
			return false;
		}
		if (paymentCategory == ServiceCategory.ETC) {
			return benefitCategory == ServiceCategory.ALL;
		}
		return benefitCategory == ServiceCategory.ALL || benefitCategory == paymentCategory;
	}

	private boolean matchesBrand(CardBenefitResponse benefit, String normalizedMerchantName) {
		List<String> brandNames = safeList(benefit.brandNames());
		if (brandNames.isEmpty()) {
			return true;
		}
		return brandNames.stream()
			.map(MerchantNameNormalizer::normalize)
			.filter(StringUtils::hasText)
			.anyMatch(normalizedMerchantName::contains);
	}

	private boolean matchesTime(CardBenefitResponse benefit, LocalDateTime calculatedAt) {
		LocalTime start = parseTime(benefit.timeStart());
		LocalTime end = parseTime(benefit.timeEnd());
		if (start == null && end == null) {
			return true;
		}
		if (start == null || end == null) {
			return false;
		}

		LocalTime current = calculatedAt.toLocalTime();
		if (!start.isAfter(end)) {
			return !current.isBefore(start) && !current.isAfter(end);
		}
		return !current.isBefore(start) || !current.isAfter(end);
	}

	private boolean matchesDay(CardBenefitResponse benefit, LocalDateTime calculatedAt) {
		return switch (defaultString(benefit.dayCondition())) {
			case "ALL", "" -> true;
			case "WEEKDAY" -> isWeekday(calculatedAt.getDayOfWeek());
			case "WEEKEND" -> !isWeekday(calculatedAt.getDayOfWeek());
			default -> false;
		};
	}

	private boolean matchesMinAmount(CardBenefitResponse benefit, long amount) {
		return benefit.minAmount() == null || amount >= benefit.minAmount();
	}

	private long calculateBaseBenefitAmount(long amount, CardBenefitTierResponse tier) {
		if (tier.flatAmount() != null) {
			return tier.flatAmount();
		}
		if (tier.rate() == null) {
			return 0L;
		}
		return BigDecimal.valueOf(amount)
			.multiply(tier.rate())
			.divide(ONE_HUNDRED, 0, RoundingMode.DOWN)
			.longValue();
	}

	private long applyAmountLimits(
		long benefitAmount,
		CardBenefitTierResponse tier,
		CardBenefitUsageResponse usage
	) {
		long limitedAmount = applySingleAmountLimit(benefitAmount, tier.maxBenefitPerUse(), 0L);
		limitedAmount = applySingleAmountLimit(limitedAmount, tier.dailyLimitAmount(), dailyAmount(usage));
		limitedAmount = applySingleAmountLimit(limitedAmount, tier.monthlyLimitAmount(), monthlyAmount(usage));
		return applySingleAmountLimit(limitedAmount, tier.yearlyLimitAmount(), yearlyAmount(usage));
	}

	private long applySingleAmountLimit(long benefitAmount, Long limitAmount, long usedAmount) {
		if (limitAmount == null) {
			return benefitAmount;
		}
		long remainingAmount = limitAmount - usedAmount;
		if (remainingAmount <= 0) {
			return 0L;
		}
		return Math.min(benefitAmount, remainingAmount);
	}

	private boolean isCountLimitExhausted(CardBenefitTierResponse tier, CardBenefitUsageResponse usage) {
		return isSingleCountLimitExhausted(tier.dailyLimitCount(), dailyCount(usage))
			|| isSingleCountLimitExhausted(tier.monthlyLimitCount(), monthlyCount(usage))
			|| isSingleCountLimitExhausted(tier.yearlyLimitCount(), yearlyCount(usage));
	}

	private boolean isSingleCountLimitExhausted(Integer limitCount, long usedCount) {
		return limitCount != null && limitCount - usedCount <= 0;
	}

	private List<String> warnings(
		CardBenefitResponse benefit,
		CardBenefitTierResponse tier,
		LocalDateTime calculatedAt
	) {
		Set<String> warnings = new LinkedHashSet<>();
		if (isNearTimeEndBoundary(benefit, calculatedAt)) {
			warnings.add(WARNING_TIME_END_BOUNDARY_NEAR);
		}
		if (hasDayCondition(benefit) && isNearDayBoundary(calculatedAt)) {
			warnings.add(WARNING_DAY_BOUNDARY_NEAR);
		}
		if (StringUtils.hasText(tier.tierDesc()) && tier.tierDesc().contains("[SHARED_LIMIT")) {
			warnings.add(WARNING_SHARED_LIMIT_NOT_APPLIED);
		}
		return new ArrayList<>(warnings);
	}

	private boolean isNearTimeEndBoundary(CardBenefitResponse benefit, LocalDateTime calculatedAt) {
		LocalTime start = parseTime(benefit.timeStart());
		LocalTime end = parseTime(benefit.timeEnd());
		if (end == null) {
			return false;
		}

		LocalDateTime endAt = LocalDateTime.of(calculatedAt.toLocalDate(), end);
		if (start != null && start.isAfter(end) && calculatedAt.toLocalTime().isAfter(start)) {
			endAt = endAt.plusDays(1);
		}
		Duration untilEnd = Duration.between(calculatedAt, endAt);
		return !untilEnd.isNegative() && untilEnd.compareTo(BOUNDARY_WARNING_THRESHOLD) <= 0;
	}

	private boolean hasDayCondition(CardBenefitResponse benefit) {
		String dayCondition = defaultString(benefit.dayCondition());
		return "WEEKDAY".equals(dayCondition) || "WEEKEND".equals(dayCondition);
	}

	private boolean isNearDayBoundary(LocalDateTime calculatedAt) {
		LocalDateTime nextDayStart = calculatedAt.toLocalDate().plusDays(1).atStartOfDay();
		Duration untilNextDay = Duration.between(calculatedAt, nextDayStart);
		return !untilNextDay.isNegative() && untilNextDay.compareTo(BOUNDARY_WARNING_THRESHOLD) <= 0;
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

	private ServiceCategory toServiceCategory(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return ServiceCategory.valueOf(value);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private LocalTime parseTime(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		return LocalTime.parse(value);
	}

	private boolean isWeekday(DayOfWeek dayOfWeek) {
		return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
	}

	private String defaultString(String value) {
		return value == null ? "" : value;
	}

	private long defaultLong(Long value) {
		return value == null ? 0L : value;
	}

	private long dailyAmount(CardBenefitUsageResponse usage) {
		return usage == null ? 0L : defaultLong(usage.dailyAmount());
	}

	private long monthlyAmount(CardBenefitUsageResponse usage) {
		return usage == null ? 0L : defaultLong(usage.monthlyAmount());
	}

	private long yearlyAmount(CardBenefitUsageResponse usage) {
		return usage == null ? 0L : defaultLong(usage.yearlyAmount());
	}

	private long dailyCount(CardBenefitUsageResponse usage) {
		return usage == null ? 0L : defaultLong(usage.dailyCount());
	}

	private long monthlyCount(CardBenefitUsageResponse usage) {
		return usage == null ? 0L : defaultLong(usage.monthlyCount());
	}

	private long yearlyCount(CardBenefitUsageResponse usage) {
		return usage == null ? 0L : defaultLong(usage.yearlyCount());
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private record CalculationContext(
		ServiceCategory paymentCategory,
		String normalizedMerchantName,
		long amount,
		LocalDateTime calculatedAt
	) {
	}

	private record BenefitCalculation(
		String benefitType,
		long amount,
		List<String> warnings
	) {
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

	private static final class BenefitAmounts {

		private long discountAmount;
		private long cashbackAmount;
		private long mileageAmount;

		void add(String benefitType, long amount) {
			switch (benefitType) {
				case "DISCOUNT" -> discountAmount += amount;
				case "CASHBACK" -> cashbackAmount += amount;
				case "MILEAGE" -> mileageAmount += amount;
				default -> {
				}
			}
		}

		long discountAmount() {
			return discountAmount;
		}

		long cashbackAmount() {
			return cashbackAmount;
		}

		long mileageAmount() {
			return mileageAmount;
		}

		long totalBenefitAmount() {
			return discountAmount + cashbackAmount + mileageAmount;
		}
	}
}
