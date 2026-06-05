package com.erumpay.recommendation.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "REC-REQ-001", "INVALID_REQUEST", "잘못된 요청입니다."),
	CARD_SERVICE_CLIENT_ERROR(
		HttpStatus.BAD_GATEWAY,
		"REC-CARD-400",
		"CARD_SERVICE_CLIENT_ERROR",
		"카드 서비스 응답을 처리할 수 없습니다."
	),
	CARD_SERVICE_UNAVAILABLE(
		HttpStatus.SERVICE_UNAVAILABLE,
		"REC-CARD-401",
		"CARD_SERVICE_UNAVAILABLE",
		"카드 서비스를 일시적으로 사용할 수 없습니다."
	),
	INTERNAL_SERVER_ERROR(
		HttpStatus.INTERNAL_SERVER_ERROR,
		"REC-SYS-900",
		"INTERNAL_SERVER_ERROR",
		"알 수 없는 내부 오류가 발생했습니다."
	);

	private final HttpStatus status;
	private final String code;
	private final String reason;
	private final String message;

	ErrorCode(HttpStatus status, String code, String reason, String message) {
		this.status = status;
		this.code = code;
		this.reason = reason;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public String getReason() {
		return reason;
	}

	public String getMessage() {
		return message;
	}
}
