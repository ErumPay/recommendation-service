package com.erumpay.recommendation.exception;

public class CardServiceUnavailableException extends RecommendationException {

	public CardServiceUnavailableException(Throwable cause) {
		super(ErrorCode.CARD_SERVICE_UNAVAILABLE, cause);
	}
}
