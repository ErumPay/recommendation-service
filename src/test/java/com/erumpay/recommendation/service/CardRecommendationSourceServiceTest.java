package com.erumpay.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erumpay.recommendation.client.CardServiceClient;
import com.erumpay.recommendation.dto.CardRecommendationSourceResponse;
import com.erumpay.recommendation.exception.CardServiceClientException;
import com.erumpay.recommendation.exception.CardServiceUnavailableException;
import com.erumpay.recommendation.exception.ErrorCode;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CardRecommendationSourceServiceTest {

	@Mock
	private CardServiceClient cardServiceClient;

	private CardRecommendationSourceService cardRecommendationSourceService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(
			Instant.parse("2026-05-26T01:00:00Z"),
			ZoneId.of("Asia/Seoul")
		);
		cardRecommendationSourceService = new CardRecommendationSourceService(cardServiceClient, clock);
	}

	@Test
	void getRecommendationSourceUsesPreviousYearMonthFromCalculationTime() {
		CardRecommendationSourceResponse expected = new CardRecommendationSourceResponse(10L, "202604", List.of());
		when(cardServiceClient.getRecommendationSource(10L, "202604")).thenReturn(expected);

		CardRecommendationSourceResponse response = cardRecommendationSourceService.getRecommendationSource(10L);

		assertThat(response).isSameAs(expected);
		assertThat(response.cards()).isEmpty();
		verify(cardServiceClient).getRecommendationSource(10L, "202604");
	}

	@Test
	void getRecommendationSourceKeepsEmptyCardsAsNormalResponse() {
		CardRecommendationSourceResponse expected = new CardRecommendationSourceResponse(10L, "202604", List.of());
		when(cardServiceClient.getRecommendationSource(10L, "202604")).thenReturn(expected);

		CardRecommendationSourceResponse response = cardRecommendationSourceService
			.getRecommendationSource(10L, "202604");

		assertThat(response.cards()).isEmpty();
	}

	@Test
	void getRecommendationSourceRejectsInvalidUserIdBeforeCallingCardService() {
		assertThatThrownBy(() -> cardRecommendationSourceService.getRecommendationSource(0L, "202604"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("userId must be positive");

		verifyNoInteractions(cardServiceClient);
	}

	@Test
	void getRecommendationSourceRejectsInvalidYearMonthBeforeCallingCardService() {
		assertThatThrownBy(() -> cardRecommendationSourceService.getRecommendationSource(10L, "2026-04"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("yearMonth must be yyyyMM");

		verifyNoInteractions(cardServiceClient);
	}

	@Test
	void getRecommendationSourceRejectsInvalidYearMonthValueBeforeCallingCardService() {
		assertThatThrownBy(() -> cardRecommendationSourceService.getRecommendationSource(10L, "202613"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("yearMonth must be yyyyMM");

		verifyNoInteractions(cardServiceClient);
	}

	@Test
	void getRecommendationSourceThrowsClientExceptionWhenResponseBodyIsNull() {
		when(cardServiceClient.getRecommendationSource(10L, "202604")).thenReturn(null);

		assertThatThrownBy(() -> cardRecommendationSourceService.getRecommendationSource(10L, "202604"))
			.isInstanceOf(CardServiceClientException.class)
			.satisfies(exception -> assertThat(((CardServiceClientException) exception).getErrorCode())
				.isEqualTo(ErrorCode.CARD_SERVICE_CLIENT_ERROR))
			.hasCauseInstanceOf(IllegalStateException.class);
	}

	@Test
	void getRecommendationSourceThrowsServiceUnavailableWhenCardServiceFails() {
		when(cardServiceClient.getRecommendationSource(10L, "202604"))
			.thenThrow(feignException(503));

		assertThatThrownBy(() -> cardRecommendationSourceService.getRecommendationSource(10L, "202604"))
			.isInstanceOf(CardServiceUnavailableException.class)
			.satisfies(exception -> assertThat(((CardServiceUnavailableException) exception).getErrorCode())
				.isEqualTo(ErrorCode.CARD_SERVICE_UNAVAILABLE));
	}

	@Test
	void getRecommendationSourceThrowsClientExceptionWhenCardServiceReturnsClientError() {
		when(cardServiceClient.getRecommendationSource(10L, "202604"))
			.thenThrow(feignException(400));

		assertThatThrownBy(() -> cardRecommendationSourceService.getRecommendationSource(10L, "202604"))
			.isInstanceOf(CardServiceClientException.class)
			.satisfies(exception -> assertThat(((CardServiceClientException) exception).getErrorCode())
				.isEqualTo(ErrorCode.CARD_SERVICE_CLIENT_ERROR));
	}

	private FeignException feignException(int status) {
		Request request = Request.create(
			Request.HttpMethod.GET,
			"/internal/v1/cards/users/10/recommendation-source",
			Map.of(),
			null,
			StandardCharsets.UTF_8,
			null
		);
		Response response = Response.builder()
			.status(status)
			.reason("card-service failure")
			.request(request)
			.build();

		return FeignException.errorStatus("getRecommendationSource", response);
	}
}
