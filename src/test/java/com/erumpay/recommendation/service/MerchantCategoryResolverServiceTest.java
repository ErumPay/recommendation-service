package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.erumpay.recommendation.domain.entity.RecommendMccMapping;
import com.erumpay.recommendation.domain.entity.RecommendMerchantKeywordOverride;
import com.erumpay.recommendation.domain.enums.MerchantCategoryMatchedBy;
import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.MerchantCategoryResolveRequest;
import com.erumpay.recommendation.repository.RecommendMccMappingRepository;
import com.erumpay.recommendation.repository.RecommendMerchantKeywordOverrideRepository;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MerchantCategoryResolverServiceTest {

	@Mock
	private RecommendMerchantKeywordOverrideRepository keywordOverrideRepository;

	@Mock
	private RecommendMccMappingRepository mccMappingRepository;

	@InjectMocks
	private MerchantCategoryResolverService merchantCategoryResolverService;

	@Test
	void resolvePrioritizesLongerKeywordWhenPriorityIsSame() {
		when(keywordOverrideRepository.findByActiveTrueOrderByPriorityDescOverrideIdAsc())
			.thenReturn(List.of(
				keywordOverride(1L, "이마트", ServiceCategory.GROCERY, 0),
				keywordOverride(2L, "이마트24", ServiceCategory.CVS, 0)
			));

		var response = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest("이마트24 강남점", "5331")
		);

		assertThat(response.serviceCategory()).isEqualTo(ServiceCategory.CVS);
		assertThat(response.matchedBy()).isEqualTo(MerchantCategoryMatchedBy.KEYWORD);
		assertThat(response.matchedKeyword()).isEqualTo("이마트24");
	}

	@Test
	void resolvePrioritizesCoupangEatsBeforeCoupangWhenPriorityIsSame() {
		when(keywordOverrideRepository.findByActiveTrueOrderByPriorityDescOverrideIdAsc())
			.thenReturn(List.of(
				keywordOverride(1L, "쿠팡", ServiceCategory.SHOPPING, 0),
				keywordOverride(2L, "쿠팡이츠", ServiceCategory.FOOD, 0)
			));

		var response = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest("쿠팡이츠 서초점", null)
		);

		assertThat(response.serviceCategory()).isEqualTo(ServiceCategory.FOOD);
		assertThat(response.matchedBy()).isEqualTo(MerchantCategoryMatchedBy.KEYWORD);
		assertThat(response.matchedKeyword()).isEqualTo("쿠팡이츠");
	}

	@Test
	void resolveFallsBackToMccMappingWhenKeywordDoesNotMatch() {
		when(keywordOverrideRepository.findByActiveTrueOrderByPriorityDescOverrideIdAsc())
			.thenReturn(List.of());
		when(mccMappingRepository.findByMccCodeAndActiveTrue("5812"))
			.thenReturn(Optional.of(mccMapping(ServiceCategory.FOOD)));

		var response = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest("로컬식당", " 5812 ")
		);

		assertThat(response.serviceCategory()).isEqualTo(ServiceCategory.FOOD);
		assertThat(response.matchedBy()).isEqualTo(MerchantCategoryMatchedBy.MCC);
		assertThat(response.matchedKeyword()).isNull();
		assertThat(response.mccCode()).isEqualTo("5812");
	}

	@Test
	void resolveFallsBackToEtcWhenKeywordAndMccDoNotMatch() {
		when(keywordOverrideRepository.findByActiveTrueOrderByPriorityDescOverrideIdAsc())
			.thenReturn(List.of());

		var response = merchantCategoryResolverService.resolve(
			new MerchantCategoryResolveRequest("알수없는가맹점", null)
		);

		assertThat(response.serviceCategory()).isEqualTo(ServiceCategory.ETC);
		assertThat(response.matchedBy()).isEqualTo(MerchantCategoryMatchedBy.DEFAULT);
		assertThat(response.matchedKeyword()).isNull();
	}

	private RecommendMerchantKeywordOverride keywordOverride(
		Long overrideId,
		String keyword,
		ServiceCategory overrideCategory,
		Integer priority
	) {
		RecommendMerchantKeywordOverride override = newInstance(RecommendMerchantKeywordOverride.class);
		ReflectionTestUtils.setField(override, "overrideId", overrideId);
		ReflectionTestUtils.setField(override, "keyword", keyword);
		ReflectionTestUtils.setField(override, "overrideCategory", overrideCategory);
		ReflectionTestUtils.setField(override, "priority", priority);
		return override;
	}

	private RecommendMccMapping mccMapping(ServiceCategory serviceCategory) {
		RecommendMccMapping mapping = newInstance(RecommendMccMapping.class);
		ReflectionTestUtils.setField(mapping, "serviceCategory", serviceCategory);
		return mapping;
	}

	private <T> T newInstance(Class<T> type) {
		try {
			Constructor<T> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Failed to create test entity: " + type.getSimpleName(), e);
		}
	}
}
