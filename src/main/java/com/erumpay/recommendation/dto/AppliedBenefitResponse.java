package com.erumpay.recommendation.dto;

public record AppliedBenefitResponse(
	Long benefitId,
	Long tierId,
	Long benefitAmount
) {

	public static AppliedBenefitResponse of(Long benefitId, Long tierId, Long benefitAmount) {
		if (benefitId == null || tierId == null || benefitAmount == null || benefitAmount <= 0) {
			return null;
		}
		return new AppliedBenefitResponse(benefitId, tierId, benefitAmount);
	}
}
