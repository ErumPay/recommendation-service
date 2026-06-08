package com.erumpay.recommendation.repository;

import com.erumpay.recommendation.domain.entity.RecommendMerchantKeywordOverride;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendMerchantKeywordOverrideRepository
	extends JpaRepository<RecommendMerchantKeywordOverride, Long> {

	List<RecommendMerchantKeywordOverride> findByActiveTrueOrderByPriorityDescOverrideIdAsc();
}
