package com.erumpay.recommendation.service;

import com.erumpay.recommendation.client.AiBestSelectorClient;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardBenefitTierResponse;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse.CardRecommendationSourceCardResponse;
import com.erumpay.recommendation.dto.AiBestSelectionContext;
import com.erumpay.recommendation.dto.AiBestSelectionResult;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.dto.MerchantCategoryResolveResponse;
import com.erumpay.recommendation.dto.PaymentUsageSummaryResponse;
import com.erumpay.recommendation.dto.RecommendationCalculateRequest;
import com.erumpay.recommendation.dto.RecommendationStrategyResultResponse;
import com.erumpay.recommendation.dto.RecommendedCardResponse;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiBestSelectorService {

	private static final List<String> FALLBACK_ORDER = List.of(
		"BENEFIT_SINGLE",
		"BENEFIT_SPLIT",
		"PERF_SINGLE",
		"PERF_SPLIT"
	);
	private static final int MAX_FUTURE_BENEFITS_PER_CARD = 5;

	private final PaymentUsageSummaryService paymentUsageSummaryService;
	private final MerchantCategoryResolverService merchantCategoryResolverService;
	private final List<AiBestSelectorClient> aiBestSelectorClients;
	private final AiSelectorProperties properties;

	@PostConstruct
	void validateConfig() {
		if (!properties.enabled()) {
			return;
		}
		if (!properties.hasSupportedProvider()) {
			throw new IllegalStateException("RECOMMENDATION_AI_PROVIDER must be gemini or openai");
		}
		if (!StringUtils.hasText(properties.resolvedApiKey())) {
			throw new IllegalStateException(
				"RECOMMENDATION_AI_API_KEY or provider API key is required when AI selector is enabled"
			);
		}
		if (!StringUtils.hasText(properties.resolvedModel())) {
			throw new IllegalStateException("RECOMMENDATION_AI_MODEL or provider model is required");
		}
		if (!properties.hasSupportedDataTier()) {
			throw new IllegalStateException("RECOMMENDATION_AI_DATA_TIER must be FREE or PAID");
		}
		if (properties.usesFreeTier() && !properties.freeTierDummyDataConfirmed()) {
			throw new IllegalStateException(
				"RECOMMENDATION_AI_FREE_TIER_DUMMY_DATA_CONFIRMED=true is required for AI Free tier"
			);
		}
	}

	public List<RecommendationStrategyResultResponse> applyBest(
		RecommendationCalculateRequest request,
		MerchantCategoryResolveResponse currentMerchant,
		CardRecommendationSourceResponse source,
		List<RecommendationStrategyResultResponse> results
	) {
		List<RecommendationStrategyResultResponse> fallbackResults = markBest(
			results,
			fallbackStrategyType(results).orElse(null)
		);
		if (!properties.enabled()) {
			return fallbackResults;
		}

		Optional<PaymentUsageSummaryResponse> usageSummary =
			paymentUsageSummaryService.getPreviousMonthSummary(request.userId());
		if (usageSummary.isEmpty()) {
			return fallbackResults;
		}

		AiBestSelectionContext context;
		try {
			context = context(request, currentMerchant, source, results, usageSummary.get());
		} catch (RuntimeException exception) {
			log.warn("AI context creation failed. paymentId={}, errorClass={}",
				request.paymentId(), exception.getClass().getSimpleName());
			return fallbackResults;
		}

		Optional<AiBestSelectorClient> client = selectedClient();
		if (client.isEmpty()) {
			log.warn("AI best selector client not found. provider={}", properties.normalizedProvider());
			return fallbackResults;
		}

		Optional<AiBestSelectionResult> selection = client.get().select(context);
		selection.ifPresent(result -> logDebugReason(request.paymentId(), result));

		return selection
			.map(AiBestSelectionResult::strategyType)
			.map(strategyType -> normalizeAiSelectedStrategy(results, strategyType))
			.filter(strategyType -> isValidStrategy(results, strategyType))
			.map(strategyType -> markBest(results, strategyType))
			.orElse(fallbackResults);
	}

	private AiBestSelectionContext context(
		RecommendationCalculateRequest request,
		MerchantCategoryResolveResponse currentMerchant,
		CardRecommendationSourceResponse source,
		List<RecommendationStrategyResultResponse> results,
		PaymentUsageSummaryResponse usageSummary
	) {
		int maxItems = properties.normalizedMaxContextItems();
		List<AiBestSelectionContext.MerchantContext> merchants = merchantContexts(usageSummary, maxItems);
		List<AiBestSelectionContext.CategoryContext> categories = categoryContexts(merchants, maxItems);
		List<AiBestSelectionContext.BrandContext> brands = brandContexts(merchants, maxItems);
		FutureBenefitRelevance futureBenefitRelevance = futureBenefitRelevance(
			currentMerchant,
			categories,
			brands
		);

		return new AiBestSelectionContext(
			request.paymentId(),
			new AiBestSelectionContext.CurrentPayment(
				request.merchantName(),
				MerchantNameNormalizer.normalize(request.merchantName()),
				request.mccCode(),
				enumName(currentMerchant.serviceCategory()),
				enumName(currentMerchant.matchedBy()),
				currentMerchant.matchedKeyword(),
				request.amount()
			),
			new AiBestSelectionContext.UsageSummary(
				nullToZero(usageSummary.totalAmount()),
				nullToZero(usageSummary.paymentCount()),
				merchants,
				categories,
				brands,
				cardUsageContexts(usageSummary, maxItems)
			),
			strategyContexts(results, source, maxItems, futureBenefitRelevance)
		);
	}

	private List<AiBestSelectionContext.MerchantContext> merchantContexts(
		PaymentUsageSummaryResponse usageSummary,
		int maxItems
	) {
		return safeList(usageSummary.merchantUsages())
			.stream()
			.filter(usage -> StringUtils.hasText(usage.merchantName()))
			.sorted(Comparator
				.comparing(PaymentUsageSummaryResponse.MerchantUsage::paidAmount,
					Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(PaymentUsageSummaryResponse.MerchantUsage::paymentCount,
					Comparator.nullsLast(Comparator.reverseOrder())))
			.limit(maxItems)
			.map(this::merchantContext)
			.toList();
	}

	private AiBestSelectionContext.MerchantContext merchantContext(
		PaymentUsageSummaryResponse.MerchantUsage usage
	) {
		MerchantCategoryResolveResponse resolved = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest(usage.merchantName(), null)
		);
		return new AiBestSelectionContext.MerchantContext(
			usage.merchantName(),
			MerchantNameNormalizer.normalize(usage.merchantName()),
			enumName(resolved.serviceCategory()),
			resolved.matchedKeyword(),
			nullToZero(usage.paidAmount()),
			nullToZero(usage.paymentCount())
		);
	}

	private List<AiBestSelectionContext.CategoryContext> categoryContexts(
		List<AiBestSelectionContext.MerchantContext> merchants,
		int maxItems
	) {
		Map<String, UsageAggregation> aggregations = new LinkedHashMap<>();
		for (AiBestSelectionContext.MerchantContext merchant : merchants) {
			aggregations.computeIfAbsent(merchant.serviceCategory(), ignored -> new UsageAggregation())
				.add(merchant.paidAmount(), merchant.paymentCount(), 1L);
		}
		return aggregations.entrySet()
			.stream()
			.map(entry -> new AiBestSelectionContext.CategoryContext(
				entry.getKey(),
				entry.getValue().paidAmount,
				entry.getValue().paymentCount,
				entry.getValue().merchantCount
			))
			.sorted(contextAmountOrder())
			.limit(maxItems)
			.toList();
	}

	private List<AiBestSelectionContext.BrandContext> brandContexts(
		List<AiBestSelectionContext.MerchantContext> merchants,
		int maxItems
	) {
		Map<String, UsageAggregation> aggregations = new LinkedHashMap<>();
		Map<String, String> brandCategories = new LinkedHashMap<>();
		for (AiBestSelectionContext.MerchantContext merchant : merchants) {
			if (!StringUtils.hasText(merchant.brandCandidate())) {
				continue;
			}
			String key = merchant.brandCandidate();
			brandCategories.putIfAbsent(key, merchant.serviceCategory());
			aggregations.computeIfAbsent(key, ignored -> new UsageAggregation())
				.add(merchant.paidAmount(), merchant.paymentCount(), 0L);
		}
		return aggregations.entrySet()
			.stream()
			.map(entry -> new AiBestSelectionContext.BrandContext(
				entry.getKey(),
				brandCategories.get(entry.getKey()),
				entry.getValue().paidAmount,
				entry.getValue().paymentCount
			))
			.sorted(brandAmountOrder())
			.limit(maxItems)
			.toList();
	}

	private List<AiBestSelectionContext.CardUsageContext> cardUsageContexts(
		PaymentUsageSummaryResponse usageSummary,
		int maxItems
	) {
		return safeList(usageSummary.cardUsages())
			.stream()
			.filter(usage -> usage.cardId() != null)
			.sorted(Comparator
				.comparing(PaymentUsageSummaryResponse.CardUsage::paidAmount,
					Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(PaymentUsageSummaryResponse.CardUsage::paymentCount,
					Comparator.nullsLast(Comparator.reverseOrder())))
			.limit(maxItems)
			.map(usage -> new AiBestSelectionContext.CardUsageContext(
				usage.cardId(),
				nullToZero(usage.paidAmount()),
				nullToZero(usage.paymentCount())
			))
			.toList();
	}

	private List<AiBestSelectionContext.Strategy> strategyContexts(
		List<RecommendationStrategyResultResponse> results,
		CardRecommendationSourceResponse source,
		int maxItems,
		FutureBenefitRelevance futureBenefitRelevance
	) {
		Map<Long, CardRecommendationSourceCardResponse> sourceCards = sourceCards(source)
			.stream()
			.filter(card -> card.cardId() != null)
			.collect(Collectors.toMap(
				CardRecommendationSourceCardResponse::cardId,
				Function.identity(),
				(first, ignored) -> first
			));

		return safeList(results)
			.stream()
			.map(result -> strategyContext(result, sourceCards, maxItems, futureBenefitRelevance))
			.toList();
	}

	private AiBestSelectionContext.Strategy strategyContext(
		RecommendationStrategyResultResponse result,
		Map<Long, CardRecommendationSourceCardResponse> sourceCards,
		int maxItems,
		FutureBenefitRelevance futureBenefitRelevance
	) {
		List<RecommendedCardResponse> cards = safeList(result.cards());
		return new AiBestSelectionContext.Strategy(
			result.strategyType(),
			nullToZero(result.totalBenefitAmount()),
			nullToZero(result.totalBenefitAmount()),
			cards.stream()
				.map(card -> cardContext(card, sourceCards.get(card.cardId()), maxItems, futureBenefitRelevance))
				.toList(),
			cards.stream()
				.flatMap(card -> safeList(card.warnings()).stream())
				.filter(Objects::nonNull)
				.distinct()
				.toList()
		);
	}

	private AiBestSelectionContext.Card cardContext(
		RecommendedCardResponse card,
		CardRecommendationSourceCardResponse sourceCard,
		int maxItems,
		FutureBenefitRelevance futureBenefitRelevance
	) {
		return new AiBestSelectionContext.Card(
			card.cardId(),
			card.cardName(),
			card.cardCompany(),
			nullToZero(card.amount()),
			nullToZero(card.totalBenefitAmount()),
			card.appliedBenefit(),
			nullToZero(card.currentPerformanceAmount()),
			card.targetPerformanceAmount(),
			card.remainingToTarget(),
			card.expectedPerformanceAmount(),
			card.willReachTarget(),
			safeList(card.warnings()),
			futureBenefitContexts(card.expectedPerformanceAmount(), sourceCard, maxItems, futureBenefitRelevance)
		);
	}

	private List<AiBestSelectionContext.FutureBenefitContext> futureBenefitContexts(
		Long expectedPerformanceAmount,
		CardRecommendationSourceCardResponse sourceCard,
		int maxItems,
		FutureBenefitRelevance futureBenefitRelevance
	) {
		if (sourceCard == null) {
			return List.of();
		}

		List<FutureBenefitCandidate> candidates = new ArrayList<>();
		for (CardBenefitResponse benefit : safeList(sourceCard.benefits())) {
			for (CardBenefitTierResponse tier : safeList(benefit.tiers())) {
				if (!isReachableTier(expectedPerformanceAmount, tier)) {
					continue;
				}
				AiBestSelectionContext.FutureBenefitContext context =
					new AiBestSelectionContext.FutureBenefitContext(
					benefit.serviceCategory(),
					!safeList(benefit.brandNames()).isEmpty(),
					safeList(benefit.brandNames()),
					benefit.benefitType(),
					rateText(tier.rate()),
					tier.flatAmount(),
					tier.maxBenefitPerUse(),
					tier.monthlyLimitAmount()
				);
				candidates.add(new FutureBenefitCandidate(
					context,
					relevanceScore(benefit, futureBenefitRelevance),
					benefitAmountScore(tier),
					tier.rate() == null ? BigDecimal.ZERO : tier.rate()
				));
			}
		}

		List<FutureBenefitCandidate> relevantCandidates = candidates.stream()
			.filter(candidate -> candidate.relevanceScore() > 0)
			.toList();
		List<FutureBenefitCandidate> selectedCandidates = relevantCandidates.isEmpty()
			? candidates
			: relevantCandidates;

		return selectedCandidates.stream()
			.sorted(this::compareFutureBenefitCandidate)
			.limit(Math.min(maxItems, MAX_FUTURE_BENEFITS_PER_CARD))
			.map(FutureBenefitCandidate::context)
			.toList();
	}

	private FutureBenefitRelevance futureBenefitRelevance(
		MerchantCategoryResolveResponse currentMerchant,
		List<AiBestSelectionContext.CategoryContext> categories,
		List<AiBestSelectionContext.BrandContext> brands
	) {
		Set<String> categoryNames = new HashSet<>();
		String currentCategory = enumName(currentMerchant.serviceCategory());
		if (StringUtils.hasText(currentCategory)) {
			categoryNames.add(currentCategory);
		}
		for (AiBestSelectionContext.CategoryContext category : categories) {
			if (StringUtils.hasText(category.serviceCategory())) {
				categoryNames.add(category.serviceCategory());
			}
		}

		Set<String> brandNames = new HashSet<>();
		String currentBrand = normalizeToken(currentMerchant.matchedKeyword());
		if (StringUtils.hasText(currentBrand)) {
			brandNames.add(currentBrand);
		}
		for (AiBestSelectionContext.BrandContext brand : brands) {
			String brandName = normalizeToken(brand.brandName());
			if (StringUtils.hasText(brandName)) {
				brandNames.add(brandName);
			}
		}
		return new FutureBenefitRelevance(categoryNames, brandNames, currentCategory, currentBrand);
	}

	private int relevanceScore(
		CardBenefitResponse benefit,
		FutureBenefitRelevance futureBenefitRelevance
	) {
		int score = 0;
		if (StringUtils.hasText(benefit.serviceCategory())) {
			if (benefit.serviceCategory().equals(futureBenefitRelevance.currentCategory())) {
				score += 100;
			} else if (futureBenefitRelevance.categoryNames().contains(benefit.serviceCategory())) {
				score += 50;
			}
		}

		List<String> benefitBrandNames = safeList(benefit.brandNames()).stream()
			.map(this::normalizeToken)
			.filter(StringUtils::hasText)
			.toList();
		if (!benefitBrandNames.isEmpty()) {
			if (benefitBrandNames.contains(futureBenefitRelevance.currentBrand())) {
				score += 100;
			} else if (benefitBrandNames.stream().anyMatch(futureBenefitRelevance.brandNames()::contains)) {
				score += 50;
			}
		}
		return score;
	}

	private long benefitAmountScore(CardBenefitTierResponse tier) {
		return Math.max(
			nullToZero(tier.maxBenefitPerUse()),
			Math.max(nullToZero(tier.monthlyLimitAmount()), nullToZero(tier.flatAmount()))
		);
	}

	private int compareFutureBenefitCandidate(FutureBenefitCandidate first, FutureBenefitCandidate second) {
		int compared = Integer.compare(second.relevanceScore(), first.relevanceScore());
		if (compared != 0) {
			return compared;
		}
		compared = Long.compare(second.amountScore(), first.amountScore());
		if (compared != 0) {
			return compared;
		}
		compared = second.rateScore().compareTo(first.rateScore());
		if (compared != 0) {
			return compared;
		}
		return String.CASE_INSENSITIVE_ORDER.compare(
			nullToEmpty(first.context().serviceCategory()),
			nullToEmpty(second.context().serviceCategory())
		);
	}

	private boolean isReachableTier(Long expectedPerformanceAmount, CardBenefitTierResponse tier) {
		if (tier == null) {
			return false;
		}
		long expected = nullToZero(expectedPerformanceAmount);
		long min = nullToZero(tier.minPrevMonthUsage());
		return expected >= min && (tier.maxPrevMonthUsage() == null || expected < tier.maxPrevMonthUsage());
	}

	private Optional<String> fallbackStrategyType(List<RecommendationStrategyResultResponse> results) {
		return FALLBACK_ORDER.stream()
			.filter(strategyType -> isValidStrategy(results, strategyType))
			.findFirst();
	}

	private boolean isValidStrategy(List<RecommendationStrategyResultResponse> results, String strategyType) {
		if (!StringUtils.hasText(strategyType)) {
			return false;
		}
		return safeList(results)
			.stream()
			.anyMatch(result -> strategyType.equals(result.strategyType())
				&& result.reason() == null
				&& !safeList(result.cards()).isEmpty());
	}

	private String normalizeAiSelectedStrategy(
		List<RecommendationStrategyResultResponse> results,
		String strategyType
	) {
		if (!"BENEFIT_SPLIT".equals(strategyType)) {
			return strategyType;
		}

		Optional<RecommendationStrategyResultResponse> benefitSingle = strategy(results, "BENEFIT_SINGLE");
		Optional<RecommendationStrategyResultResponse> benefitSplit = strategy(results, "BENEFIT_SPLIT");
		if (benefitSingle.isPresent()
			&& benefitSplit.isPresent()
			&& safeList(benefitSplit.get().cards()).size() == 1
			&& nullToZero(benefitSingle.get().totalBenefitAmount()) >= nullToZero(benefitSplit.get().totalBenefitAmount())) {
			return "BENEFIT_SINGLE";
		}
		return strategyType;
	}

	private Optional<RecommendationStrategyResultResponse> strategy(
		List<RecommendationStrategyResultResponse> results,
		String strategyType
	) {
		return safeList(results)
			.stream()
			.filter(result -> strategyType.equals(result.strategyType())
				&& result.reason() == null
				&& !safeList(result.cards()).isEmpty())
			.findFirst();
	}

	private List<RecommendationStrategyResultResponse> markBest(
		List<RecommendationStrategyResultResponse> results,
		String strategyType
	) {
		return safeList(results)
			.stream()
			.map(result -> result.withBest(strategyType != null && strategyType.equals(result.strategyType())))
			.toList();
	}

	private void logDebugReason(Long paymentId, AiBestSelectionResult result) {
		if (!properties.debugReasonLogEnabled()) {
			return;
		}
		log.info("AI best selector result. provider={}, paymentId={}, selectedStrategyType={}, debugReason={}",
			properties.normalizedProvider(), paymentId, result.strategyType(), truncate(result.debugReason()));
	}

	private Optional<AiBestSelectorClient> selectedClient() {
		String provider = properties.normalizedProvider();
		return aiBestSelectorClients.stream()
			.filter(client -> provider.equals(client.provider()))
			.findFirst();
	}

	private String truncate(String value) {
		if (value == null || value.length() <= 300) {
			return value;
		}
		return value.substring(0, 300);
	}

	private List<CardRecommendationSourceCardResponse> sourceCards(CardRecommendationSourceResponse source) {
		if (source == null || source.cards() == null) {
			return List.of();
		}
		return source.cards();
	}

	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private long nullToZero(Long value) {
		return value == null ? 0L : value;
	}

	private String enumName(Enum<?> value) {
		return value == null ? null : value.name();
	}

	private String rateText(BigDecimal rate) {
		return rate == null ? null : rate.stripTrailingZeros().toPlainString();
	}

	private String normalizeToken(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		return value.replaceAll("\\s+", "").toLowerCase();
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private Comparator<AiBestSelectionContext.CategoryContext> contextAmountOrder() {
		return Comparator
			.comparing(AiBestSelectionContext.CategoryContext::paidAmount, Comparator.reverseOrder())
			.thenComparing(AiBestSelectionContext.CategoryContext::paymentCount, Comparator.reverseOrder());
	}

	private Comparator<AiBestSelectionContext.BrandContext> brandAmountOrder() {
		return Comparator
			.comparing(AiBestSelectionContext.BrandContext::paidAmount, Comparator.reverseOrder())
			.thenComparing(AiBestSelectionContext.BrandContext::paymentCount, Comparator.reverseOrder());
	}

	private static class UsageAggregation {

		private long paidAmount;
		private long paymentCount;
		private long merchantCount;

		private void add(Long paidAmount, Long paymentCount, Long merchantCount) {
			this.paidAmount += paidAmount == null ? 0L : paidAmount;
			this.paymentCount += paymentCount == null ? 0L : paymentCount;
			this.merchantCount += merchantCount == null ? 0L : merchantCount;
		}
	}

	private record FutureBenefitRelevance(
		Set<String> categoryNames,
		Set<String> brandNames,
		String currentCategory,
		String currentBrand
	) {
	}

	private record FutureBenefitCandidate(
		AiBestSelectionContext.FutureBenefitContext context,
		int relevanceScore,
		long amountScore,
		BigDecimal rateScore
	) {
	}
}
