package com.erumpay.recommendation.domain.entity;

import com.erumpay.recommendation.domain.enums.ServiceCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "recommend_merchant_keyword_override")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendMerchantKeywordOverride {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "override_id")
	private Long overrideId;

	@Column(name = "keyword", nullable = false, unique = true, length = 100)
	private String keyword;

	@Enumerated(EnumType.STRING)
	@Column(name = "override_category", nullable = false)
	private ServiceCategory overrideCategory;

	@Column(name = "priority", nullable = false)
	private Integer priority;

	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "is_active", nullable = false)
	private Boolean active;
}
