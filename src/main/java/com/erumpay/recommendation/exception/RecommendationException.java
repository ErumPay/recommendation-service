package com.erumpay.recommendation.exception;

public abstract class RecommendationException extends RuntimeException {

	private final ErrorCode errorCode;

	protected RecommendationException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	protected RecommendationException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.getMessage(), cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public org.springframework.http.HttpStatus getStatus() {
		return errorCode.getStatus();
	}

	public String getCode() {
		return errorCode.getCode();
	}

	public String getReason() {
		return errorCode.getReason();
	}
}
