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
@Table(name = "recommend_mcc_mapping")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendMccMapping {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "mapping_id")
	private Long mappingId;

	@Column(name = "mcc_code", nullable = false, unique = true, columnDefinition = "CHAR(4)")
	private String mccCode;

	@Column(name = "mcc_description", length = 100)
	private String mccDescription;

	@Enumerated(EnumType.STRING)
	@Column(name = "service_category", nullable = false)
	private ServiceCategory serviceCategory;

	@Column(name = "created_at", insertable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private LocalDateTime updatedAt;

	@Column(name = "is_active", nullable = false)
	private Boolean active;
}
