package com.erumpay.recommendation.service;

import java.util.Locale;

public final class MerchantNameNormalizer {

	private MerchantNameNormalizer() {
	}

	// [be] 이준혁 260526 1032 | 가맹점명과 keyword를 같은 기준으로 비교하기 위해 공백과 특수문자를 제거한다.
	public static String normalize(String value) {
		if (value == null) {
			return "";
		}

		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return normalized.replaceAll("[^0-9a-z가-힣]", "");
	}
}
