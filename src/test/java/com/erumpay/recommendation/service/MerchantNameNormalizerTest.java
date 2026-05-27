package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MerchantNameNormalizerTest {

	@Test
	void normalizeRemovesSpacesAndSpecialCharacters() {
		String result = MerchantNameNormalizer.normalize(" SSG.COM 강남 점!! ");

		assertThat(result).isEqualTo("ssgcom강남점");
	}

	@Test
	void normalizeReturnsEmptyStringWhenValueIsNull() {
		String result = MerchantNameNormalizer.normalize(null);

		assertThat(result).isEmpty();
	}
}
