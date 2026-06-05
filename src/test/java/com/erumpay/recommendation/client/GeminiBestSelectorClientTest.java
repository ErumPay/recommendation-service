package com.erumpay.recommendation.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.erumpay.recommendation.dto.AiBestSelectionContext;
import com.erumpay.recommendation.dto.AiBestSelectionResult;
import com.erumpay.recommendation.service.AiSelectorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GeminiBestSelectorClientTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private HttpServer server;
	private CapturedRequest capturedRequest;
	private int responseStatus;
	private String responseBody;

	@BeforeEach
	void setUp() throws IOException {
		responseStatus = 200;
		responseBody = """
			{
			  "candidates": [
			    {
			      "content": {
			        "parts": [
			          {
			            "text": "{\\"strategyType\\":\\"PERF_SINGLE\\",\\"debugReason\\":\\"실적 달성 가능\\"}"
			          }
			        ]
			      }
			    }
			  ]
			}
			""";
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void selectSendsApiKeyHeaderAndStructuredOutputSchema() throws Exception {
		Optional<AiBestSelectionResult> result = client().select(context());

		assertThat(result).contains(new AiBestSelectionResult("PERF_SINGLE", "실적 달성 가능"));
		assertThat(capturedRequest.method()).isEqualTo("POST");
		assertThat(capturedRequest.path()).isEqualTo("/v1beta/models/gemini-2.5-flash-lite:generateContent");
		assertThat(capturedRequest.query()).isNull();
		assertThat(capturedRequest.apiKeyHeader()).isEqualTo("test-key");

		JsonNode body = objectMapper.readTree(capturedRequest.body());
		JsonNode generationConfig = body.get("generationConfig");
		assertThat(generationConfig.get("responseMimeType").asText()).isEqualTo("application/json");
		assertThat(StreamSupport.stream(generationConfig.get("responseJsonSchema").get("properties")
				.get("strategyType").get("enum").spliterator(), false)
			.map(JsonNode::asText)
			.toList())
			.containsExactly("BENEFIT_SINGLE", "PERF_SINGLE", "BENEFIT_SPLIT", "PERF_SPLIT");
	}

	@Test
	void selectReturnsEmptyWhenGeminiReturnsServerError() {
		responseStatus = 500;
		responseBody = "{\"error\":{\"message\":\"server error\"}}";

		Optional<AiBestSelectionResult> result = client().select(context());

		assertThat(result).isEmpty();
	}

	@Test
	void selectReturnsEmptyWhenGeminiReturnsInvalidSelectionJson() {
		responseBody = """
			{
			  "candidates": [
			    {
			      "content": {
			        "parts": [
			          {
			            "text": "not-json"
			          }
			        ]
			      }
			    }
			  ]
			}
			""";

		Optional<AiBestSelectionResult> result = client().select(context());

		assertThat(result).isEmpty();
	}

	private GeminiBestSelectorClient client() {
		RestClient restClient = RestClient.builder()
			.baseUrl("http://localhost:" + server.getAddress().getPort())
			.build();

		return new GeminiBestSelectorClient(restClient, objectMapper, properties());
	}

	private AiSelectorProperties properties() {
		return new AiSelectorProperties(
			true,
			"gemini",
			"test-key",
			null,
			1500L,
			20,
			false,
			false,
			"FREE",
			true,
			new AiSelectorProperties.ProviderProperties(
				null,
				"gemini-2.5-flash-lite",
				"http://localhost:" + server.getAddress().getPort()
			),
			new AiSelectorProperties.ProviderProperties(
				null,
				"gpt-4o-mini",
				"http://localhost:" + server.getAddress().getPort()
			)
		);
	}

	private AiBestSelectionContext context() {
		return new AiBestSelectionContext(
			123L,
			new AiBestSelectionContext.CurrentPayment(
				"스타벅스 강남점",
				"스타벅스강남점",
				"5814",
				"CAFE",
				"KEYWORD",
				"스타벅스",
				10_000L
			),
			new AiBestSelectionContext.UsageSummary(
				50_000L,
				5L,
				List.of(),
				List.of(),
				List.of(),
				List.of()
			),
			List.of()
		);
	}

	private void handle(HttpExchange exchange) throws IOException {
		capturedRequest = new CapturedRequest(
			exchange.getRequestMethod(),
			exchange.getRequestURI().getPath(),
			exchange.getRequestURI().getQuery(),
			exchange.getRequestHeaders().getFirst("x-goog-api-key"),
			new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
		);
		byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(responseStatus, responseBytes.length);
		exchange.getResponseBody().write(responseBytes);
		exchange.close();
	}

	private record CapturedRequest(
		String method,
		String path,
		String query,
		String apiKeyHeader,
		String body
	) {
	}
}
