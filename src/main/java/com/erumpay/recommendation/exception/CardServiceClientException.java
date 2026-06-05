package com.erumpay.recommendation.exception;

public class CardServiceClientException extends RecommendationException {

	public CardServiceClientException(Throwable cause) {
		super(ErrorCode.CARD_SERVICE_CLIENT_ERROR, cause);
	}
}
