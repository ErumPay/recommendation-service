package com.erumpay.recommendation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class CardServiceClientException extends RuntimeException {

	public CardServiceClientException(Throwable cause) {
		super("card-service 요청 처리 실패", cause);
	}
}
