package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitUsageResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BenefitScoreCalculator {

	private static final String WARNING_SHARED_LIMIT_NOT_APPLIED = "SHARED_LIMIT_NOT_APPLIED";
	private static final String WARNING_TIME_END_BOUNDARY_NEAR = "TIME_END_BOUNDARY_NEAR";
	private static final String WARNING_DAY_BOUNDARY_NEAR = "DAY_BOUNDARY_NEAR";
	private static final Duration BOUNDARY_WARNING_THRESHOLD = Duration.ofMinutes(10);
	private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

	// [be] 이준혁 260527 2306 | 카드 1장의 적용 가능한 혜택 중 매출건당 예상 혜택이 가장 큰 1개만 점수로 환산한다.
	public BenefitScore calculate(
		CardRecommendationSourceCardResponse card,
		BenefitScoreContext context
	) {
		return safeList(card.benefits()).stream()
			.map(benefit -> calculateBenefit(benefit, card, context))
			.flatMap(Optional::stream)
			.sorted(benefitCalculationPriority())
			.findFirst()
			.map(this::toBenefitScore)
			.orElseGet(this::emptyBenefitScore);
	}

	// [be] 이준혁 260526 1450 | category, brand, 시간, 실적 tier, 한도를 모두 통과한 혜택만 금액으로 환산한다.
	private Optional<BenefitCalculation> calculateBenefit(
		CardBenefitResponse benefit,
		CardRecommendationSourceCardResponse card,
		BenefitScoreContext context
	) {
		if (!isSupportedBenefitType(benefit.benefitType())
			|| !matchesCategory(benefit, context.paymentCategory())
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
			benefit.benefitId(),
			benefit.benefitType(),
			benefitAmount,
			warnings(benefit, selectedTier, context.calculatedAt())
		));
	}

	// [be] 이준혁 260527 2306 | 최고 혜택이 같으면 benefitId가 작은 혜택을 선택해 카드 내부 결과를 고정한다.
	private Comparator<BenefitCalculation> benefitCalculationPriority() {
		return Comparator
			.comparingLong(BenefitCalculation::amount)
			.reversed()
			.thenComparing(
				BenefitCalculation::benefitId,
				Comparator.nullsLast(Comparator.naturalOrder())
			);
	}

	private BenefitScore toBenefitScore(BenefitCalculation calculation) {
		return switch (calculation.benefitType()) {
			case "DISCOUNT" -> new BenefitScore(
				calculation.amount(),
				0L,
				0L,
				calculation.amount(),
				calculation.warnings()
			);
			case "CASHBACK" -> new BenefitScore(
				0L,
				calculation.amount(),
				0L,
				calculation.amount(),
				calculation.warnings()
			);
			case "MILEAGE" -> new BenefitScore(
				0L,
				0L,
				calculation.amount(),
				calculation.amount(),
				calculation.warnings()
			);
			default -> emptyBenefitScore();
		};
	}

	private BenefitScore emptyBenefitScore() {
		return new BenefitScore(0L, 0L, 0L, 0L, List.of());
	}

	// [be] 이준혁 260526 1450 | 전월실적 조건은 min 이상, max 미만인 tier 중 가장 높은 min 구간을 선택한다.
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

	private boolean isSupportedBenefitType(String benefitType) {
		return "DISCOUNT".equals(benefitType) || "CASHBACK".equals(benefitType) || "MILEAGE".equals(benefitType);
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
		if (isInvalidTime(benefit.timeStart(), start) || isInvalidTime(benefit.timeEnd(), end)) {
			return false;
		}
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
		try {
			return LocalTime.parse(value);
		} catch (DateTimeException exception) {
			return null;
		}
	}

	private boolean isInvalidTime(String value, LocalTime parsedTime) {
		return StringUtils.hasText(value) && parsedTime == null;
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

	public record BenefitScoreContext(
		ServiceCategory paymentCategory,
		String normalizedMerchantName,
		long amount,
		LocalDateTime calculatedAt
	) {
	}

	public record BenefitScore(
		long discountAmount,
		long cashbackAmount,
		long mileageAmount,
		long totalBenefitAmount,
		List<String> warnings
	) {
	}

	private record BenefitCalculation(
		Long benefitId,
		String benefitType,
		long amount,
		List<String> warnings
	) {
	}
}
