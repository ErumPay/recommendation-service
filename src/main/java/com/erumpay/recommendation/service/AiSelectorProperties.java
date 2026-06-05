package com.erumpay.recommendation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "recommendation.ai")
public record AiSelectorProperties(
	boolean enabled,
	String provider,
	String apiKey,
	String model,
	long timeoutMs,
	int maxContextItems,
	boolean debugReasonLogEnabled,
	boolean contextLogEnabled,
	String dataTier,
	boolean freeTierDummyDataConfirmed,
	ProviderProperties gemini,
	ProviderProperties openai
) {

	private static final String GEMINI = "gemini";
	private static final String OPENAI = "openai";
	private static final String FREE_TIER = "FREE";
	private static final String PAID_TIER = "PAID";

	public String normalizedProvider() {
		if (!StringUtils.hasText(provider)) {
			return GEMINI;
		}
		return provider.trim().toLowerCase();
	}

	public boolean hasSupportedProvider() {
		String value = normalizedProvider();
		return GEMINI.equals(value) || OPENAI.equals(value);
	}

	public String resolvedApiKey() {
		if (StringUtils.hasText(apiKey)) {
			return apiKey;
		}
		ProviderProperties providerProperties = providerProperties();
		return providerProperties == null ? null : providerProperties.apiKey();
	}

	public String resolvedModel() {
		if (StringUtils.hasText(model)) {
			return model;
		}
		ProviderProperties providerProperties = providerProperties();
		return providerProperties == null ? null : providerProperties.model();
	}

	public String resolvedBaseUrl() {
		ProviderProperties providerProperties = providerProperties();
		return providerProperties == null ? null : providerProperties.baseUrl();
	}

	public String geminiBaseUrl() {
		return gemini == null ? null : gemini.baseUrl();
	}

	public String openaiBaseUrl() {
		return openai == null ? null : openai.baseUrl();
	}

	public int normalizedMaxContextItems() {
		return Math.max(maxContextItems, 1);
	}

	public boolean usesFreeTier() {
		return FREE_TIER.equals(normalizedDataTier());
	}

	public boolean hasSupportedDataTier() {
		String value = normalizedDataTier();
		return FREE_TIER.equals(value) || PAID_TIER.equals(value);
	}

	public String normalizedDataTier() {
		if (dataTier == null || dataTier.isBlank()) {
			return FREE_TIER;
		}
		return dataTier.trim().toUpperCase();
	}

	private ProviderProperties providerProperties() {
		return OPENAI.equals(normalizedProvider()) ? openai : gemini;
	}

	public record ProviderProperties(
		String apiKey,
		String model,
		String baseUrl
	) {
	}
}
