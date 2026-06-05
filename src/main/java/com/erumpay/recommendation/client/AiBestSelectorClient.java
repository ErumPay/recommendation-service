package com.erumpay.recommendation.client;

import com.erumpay.recommendation.dto.AiBestSelectionContext;
import com.erumpay.recommendation.dto.AiBestSelectionResult;
import java.util.Optional;

public interface AiBestSelectorClient {

	String provider();

	Optional<AiBestSelectionResult> select(AiBestSelectionContext context);
}
