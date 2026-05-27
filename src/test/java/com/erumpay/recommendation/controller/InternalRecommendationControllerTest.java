package com.erumpay.recommendation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erumpay.recommendation.domain.enums.MerchantCategoryMatchedBy;
import com.erumpay.recommendation.domain.enums.ServiceCategory;
import com.erumpay.recommendation.dto.MerchantCategoryResolveResponse;
import com.erumpay.recommendation.service.MerchantCategoryResolverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalRecommendationController.class)
class InternalRecommendationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MerchantCategoryResolverService merchantCategoryResolverService;

	@Test
	void resolveMerchantCategoryReturnsResolvedCategory() throws Exception {
		when(merchantCategoryResolverService.resolve(any()))
			.thenReturn(new MerchantCategoryResolveResponse(
				"이마트24 강남점",
				"5331",
				ServiceCategory.CVS,
				MerchantCategoryMatchedBy.KEYWORD,
				"이마트24"
			));

		mockMvc.perform(post("/internal/v1/recommendations/merchant-category/resolve")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "merchantName": "이마트24 강남점",
					  "mccCode": "5331"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.serviceCategory").value("CVS"))
			.andExpect(jsonPath("$.matchedBy").value("KEYWORD"))
			.andExpect(jsonPath("$.matchedKeyword").value("이마트24"));
	}

	@Test
	void resolveMerchantCategoryRejectsBlankMerchantName() throws Exception {
		mockMvc.perform(post("/internal/v1/recommendations/merchant-category/resolve")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "merchantName": " ",
					  "mccCode": "5331"
					}
					"""))
			.andExpect(status().isBadRequest());
	}
}
