package com.erumpay.recommendation.client;

import com.erumpay.recommendation.dto.AiBestSelectionContext;
import com.erumpay.recommendation.dto.AiBestSelectionResult;
import com.erumpay.recommendation.service.AiSelectorProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class GeminiBestSelectorClient implements AiBestSelectorClient {

	private final RestClient geminiRestClient;
	private final ObjectMapper objectMapper;
	private final AiSelectorProperties properties;

	public GeminiBestSelectorClient(
		@Qualifier("geminiAiRestClient") RestClient geminiRestClient,
		ObjectMapper objectMapper,
		AiSelectorProperties properties
	) {
		this.geminiRestClient = geminiRestClient;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	@Override
	public String provider() {
		return "gemini";
	}

	@Override
	public Optional<AiBestSelectionResult> select(AiBestSelectionContext context) {
		try {
			GeminiGenerateContentResponse response = geminiRestClient.post()
				.uri("/v1beta/models/{model}:generateContent", properties.resolvedModel())
				.header("x-goog-api-key", properties.resolvedApiKey())
				.body(requestBody(context))
				.retrieve()
				.body(GeminiGenerateContentResponse.class);

			return extractText(response)
				.flatMap(this::parseSelection);
		} catch (JsonProcessingException exception) {
			log.warn("AI best selector failed. provider=gemini, paymentId={}, errorClass={}",
				context.paymentId(), exception.getClass().getSimpleName());
			return Optional.empty();
		} catch (RuntimeException exception) {
			log.warn("AI best selector failed. provider=gemini, paymentId={}, errorClass={}",
				context.paymentId(), exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private GeminiGenerateContentRequest requestBody(AiBestSelectionContext context)
		throws JsonProcessingException {
		String contextJson = objectMapper.writeValueAsString(context);
		logContextIfEnabled(context, contextJson);

		return new GeminiGenerateContentRequest(
			List.of(new Content(List.of(new Part(AiBestSelectorPrompt.prompt(contextJson))))),
			new GenerationConfig(
				"application/json",
				AiBestSelectorPrompt.jsonSchema(),
				0.2
			)
		);
	}

	private void logContextIfEnabled(AiBestSelectionContext context, String contextJson) {
		if (!properties.contextLogEnabled()) {
			return;
		}
		log.info("AI best selector context. provider=gemini, paymentId={}, context={}", context.paymentId(), contextJson);
	}

	private Optional<String> extractText(GeminiGenerateContentResponse response) {
		if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
			return Optional.empty();
		}
		Candidate candidate = response.candidates().getFirst();
		if (candidate.content() == null || candidate.content().parts() == null
			|| candidate.content().parts().isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(candidate.content().parts().getFirst().text());
	}

	private Optional<AiBestSelectionResult> parseSelection(String text) {
		try {
			return Optional.of(objectMapper.readValue(text, AiBestSelectionResult.class));
		} catch (JsonProcessingException exception) {
			log.warn("AI best selector returned invalid JSON. provider=gemini, errorClass={}",
				exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private record GeminiGenerateContentRequest(
		List<Content> contents,
		GenerationConfig generationConfig
	) {
	}

	private record Content(
		List<Part> parts
	) {
	}

	private record Part(
		String text
	) {
	}

	private record GenerationConfig(
		String responseMimeType,
		Map<String, Object> responseJsonSchema,
		Double temperature
	) {
	}

	private record GeminiGenerateContentResponse(
		List<Candidate> candidates
	) {
	}

	private record Candidate(
		Content content
	) {
	}
}
