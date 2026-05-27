package com.erumpay.recommendation.controller;

import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.dto.MerchantCategoryResolveResponse;
import com.erumpay.recommendation.service.MerchantCategoryResolverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/recommendations")
public class InternalRecommendationController {

	private final MerchantCategoryResolverService merchantCategoryResolverService;

	@PostMapping("/merchant-category/resolve")
	// [be] 이준혁 260526 1032 | 추천 계산 전 가맹점 카테고리 해석 로직을 검증할 수 있도록 내부 API로 노출한다.
	public MerchantCategoryResolveResponse resolveMerchantCategory(
		@Valid @RequestBody MerchantCategoryResolveRequest request
	) {
		return merchantCategoryResolverService.resolve(request);
	}
}
