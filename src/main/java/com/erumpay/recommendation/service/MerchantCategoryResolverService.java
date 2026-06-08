package com.erumpay.recommendation.service;

import com.erumpay.recommendation.domain.entity.RecommendMerchantKeywordOverride;
import com.erumpay.recommendation.domain.enums.MerchantCategoryMatchedBy;
import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.dto.MerchantCategoryResolveResponse;
import com.erumpay.recommendation.repository.RecommendMccMappingRepository;
import com.erumpay.recommendation.repository.RecommendMerchantKeywordOverrideRepository;
import java.util.Comparator;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MerchantCategoryResolverService {

	private final RecommendMerchantKeywordOverrideRepository keywordOverrideRepository;
	private final RecommendMccMappingRepository mccMappingRepository;

	// [be] 이준혁 260526 1032 | 가맹점명 keyword가 MCC보다 구체적이므로 keyword 보정 후 MCC, ETC 순서로 fallback한다.
	public MerchantCategoryResolveResponse resolve(MerchantCategoryResolveRequest request) {
		String normalizedMerchantName = MerchantNameNormalizer.normalize(request.merchantName());

		return keywordOverrideRepository.findByActiveTrueOrderByPriorityDescOverrideIdAsc()
			.stream()
			.filter(override -> matchesKeyword(normalizedMerchantName, override))
			.sorted(keywordPriority())
			.findFirst()
			.map(override -> keywordResponse(request, override))
			.or(() -> mccResponse(request))
			.orElseGet(() -> defaultResponse(request));
	}

	// [be] 이준혁 260526 1032 | 지점명이나 부가 문구가 붙어도 매칭되도록 정규화된 가맹점명에 keyword가 포함되는지 확인한다.
	private boolean matchesKeyword(String normalizedMerchantName, RecommendMerchantKeywordOverride override) {
		String normalizedKeyword = MerchantNameNormalizer.normalize(override.getKeyword());
		return StringUtils.hasText(normalizedKeyword) && normalizedMerchantName.contains(normalizedKeyword);
	}

	// [be] 이준혁 260526 1032 | 우선순위가 같으면 더 구체적인 긴 keyword를 고르고, 그래도 같으면 ID로 결과를 고정한다.
	private Comparator<RecommendMerchantKeywordOverride> keywordPriority() {
		return Comparator
			.comparing(RecommendMerchantKeywordOverride::getPriority,
				Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(override -> MerchantNameNormalizer.normalize(override.getKeyword()).length(),
				Comparator.reverseOrder())
			.thenComparing(RecommendMerchantKeywordOverride::getOverrideId,
				Comparator.nullsLast(Comparator.naturalOrder()));
	}

	private MerchantCategoryResolveResponse keywordResponse(
		MerchantCategoryResolveRequest request,
		RecommendMerchantKeywordOverride override
	) {
		return new MerchantCategoryResolveResponse(
			request.merchantName(),
			trimToNull(request.mccCode()),
			override.getOverrideCategory(),
			MerchantCategoryMatchedBy.KEYWORD,
			override.getKeyword()
		);
	}

	// [be] 이준혁 260526 1032 | keyword로 보정하지 못한 가맹점은 카드사 MCC 분류를 사용해 추천 카테고리를 결정한다.
	private java.util.Optional<MerchantCategoryResolveResponse> mccResponse(MerchantCategoryResolveRequest request) {
		String mccCode = trimToNull(request.mccCode());
		if (mccCode == null) {
			return java.util.Optional.empty();
		}

		return mccMappingRepository.findByMccCodeAndActiveTrue(mccCode)
			.map(mapping -> new MerchantCategoryResolveResponse(
				request.merchantName(),
				mccCode,
				mapping.getServiceCategory(),
				MerchantCategoryMatchedBy.MCC,
				null
			));
	}

	// [be] 이준혁 260526 1032 | keyword와 MCC 모두 실패해도 추천 계산이 중단되지 않도록 ETC 카테고리로 보낸다.
	private MerchantCategoryResolveResponse defaultResponse(MerchantCategoryResolveRequest request) {
		return new MerchantCategoryResolveResponse(
			request.merchantName(),
			trimToNull(request.mccCode()),
			ServiceCategory.ETC,
			MerchantCategoryMatchedBy.DEFAULT,
			null
		);
	}

	private String trimToNull(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		return Objects.requireNonNull(value).trim();
	}
}
