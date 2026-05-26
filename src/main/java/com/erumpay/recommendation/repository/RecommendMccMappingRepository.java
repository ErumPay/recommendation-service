package com.erumpay.recommendation.repository;

import com.erumpay.recommendation.domain.entity.RecommendMccMapping;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendMccMappingRepository extends JpaRepository<RecommendMccMapping, Long> {

	Optional<RecommendMccMapping> findByMccCodeAndActiveTrue(String mccCode);
}
