package com.erumpay.recommendation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class CardServiceUnavailableException extends RuntimeException {

	public CardServiceUnavailableException(Throwable cause) {
		super("card-service 연동 실패", cause);
	}
}
