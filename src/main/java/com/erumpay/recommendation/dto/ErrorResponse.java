package com.erumpay.recommendation.dto;

import com.erumpay.recommendation.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;

public record ErrorResponse(
	LocalDateTime timestamp,
	int status,
	String error,
	String code,
	String reason,
	String message,
	List<ErrorDetail> details,
	String correlationId,
	String path
) {

	public static ErrorResponse of(
		ErrorCode errorCode,
		String message,
		String path,
		List<ErrorDetail> details,
		String correlationId
	) {
		return new ErrorResponse(
			LocalDateTime.now(),
			errorCode.getStatus().value(),
			errorCode.getStatus().name(),
			errorCode.getCode(),
			errorCode.getReason(),
			message == null ? errorCode.getMessage() : message,
			details == null ? List.of() : details,
			correlationId,
			path
		);
	}

	public record ErrorDetail(
		String field,
		String message
	) {
	}
}
