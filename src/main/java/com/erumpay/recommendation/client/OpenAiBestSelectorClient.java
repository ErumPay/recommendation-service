package com.erumpay.recommendation.client;

import com.erumpay.recommendation.dto.AiBestSelectionContext;
import com.erumpay.recommendation.dto.AiBestSelectionResult;
import com.erumpay.recommendation.service.AiSelectorProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class OpenAiBestSelectorClient implements AiBestSelectorClient {

	private final RestClient openAiRestClient;
	private final ObjectMapper objectMapper;
	private final AiSelectorProperties properties;

	public OpenAiBestSelectorClient(
		@Qualifier("openAiRestClient") RestClient openAiRestClient,
		ObjectMapper objectMapper,
		AiSelectorProperties properties
	) {
		this.openAiRestClient = openAiRestClient;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	@Override
	public String provider() {
		return "openai";
	}

	@Override
	public Optional<AiBestSelectionResult> select(AiBestSelectionContext context) {
		try {
			JsonNode response = openAiRestClient.post()
				.uri("/v1/responses")
				.header("Authorization", "Bearer " + properties.resolvedApiKey())
				.body(requestBody(context))
				.retrieve()
				.body(JsonNode.class);

			return extractText(response)
				.flatMap(this::parseSelection);
		} catch (JsonProcessingException exception) {
			log.warn("AI best selector failed. provider=openai, paymentId={}, errorClass={}",
				context.paymentId(), exception.getClass().getSimpleName());
			return Optional.empty();
		} catch (RuntimeException exception) {
			log.warn("AI best selector failed. provider=openai, paymentId={}, errorClass={}",
				context.paymentId(), exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private OpenAiResponsesRequest requestBody(AiBestSelectionContext context)
		throws JsonProcessingException {
		String contextJson = objectMapper.writeValueAsString(context);
		logContextIfEnabled(context, contextJson);

		return new OpenAiResponsesRequest(
			properties.resolvedModel(),
			List.of(
				new InputMessage("system", "Return only JSON that matches the requested schema."),
				new InputMessage("user", AiBestSelectorPrompt.prompt(contextJson))
			),
			new TextConfig(new JsonSchemaFormat(
				"json_schema",
				"erumpay_best_strategy",
				"Selected ErumPay recommendation strategy.",
				true,
				AiBestSelectorPrompt.jsonSchema()
			)),
			0.2
		);
	}

	private void logContextIfEnabled(AiBestSelectionContext context, String contextJson) {
		if (!properties.contextLogEnabled()) {
			return;
		}
		log.info("AI best selector context. provider=openai, paymentId={}, context={}", context.paymentId(), contextJson);
	}

	private Optional<String> extractText(JsonNode response) {
		if (response == null) {
			return Optional.empty();
		}
		JsonNode outputText = response.get("output_text");
		if (outputText != null && outputText.isTextual()) {
			return Optional.of(outputText.asText());
		}
		for (JsonNode output : response.path("output")) {
			for (JsonNode content : output.path("content")) {
				JsonNode text = content.get("text");
				if (text != null && text.isTextual()) {
					return Optional.of(text.asText());
				}
			}
		}
		return Optional.empty();
	}

	private Optional<AiBestSelectionResult> parseSelection(String text) {
		try {
			return Optional.of(objectMapper.readValue(text, AiBestSelectionResult.class));
		} catch (JsonProcessingException exception) {
			log.warn("AI best selector returned invalid JSON. provider=openai, errorClass={}",
				exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private record OpenAiResponsesRequest(
		String model,
		List<InputMessage> input,
		TextConfig text,
		Double temperature
	) {
	}

	private record InputMessage(
		String role,
		String content
	) {
	}

	private record TextConfig(
		JsonSchemaFormat format
	) {
	}

	private record JsonSchemaFormat(
		String type,
		String name,
		String description,
		Boolean strict,
		Map<String, Object> schema
	) {
	}
}
