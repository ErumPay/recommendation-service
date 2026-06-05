package com.erumpay.recommendation.config;

import com.erumpay.recommendation.service.AiSelectorProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiSelectorProperties.class)
public class AiClientConfig {

	@Bean
	RestClient geminiAiRestClient(AiSelectorProperties properties) {
		return restClient(properties.geminiBaseUrl(), properties);
	}

	@Bean
	RestClient openAiRestClient(AiSelectorProperties properties) {
		return restClient(properties.openaiBaseUrl(), properties);
	}

	private RestClient restClient(String baseUrl, AiSelectorProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		Duration timeout = Duration.ofMillis(Math.max(properties.timeoutMs(), 1));
		requestFactory.setConnectTimeout(timeout);
		requestFactory.setReadTimeout(timeout);

		return RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
	}
}
