package com.hihops.hops.itsystem.domain;

import com.hihops.hops.global.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 시스템 기능. 시스템 안의 기능(문서 조회·결재 상태 등)이다 (docs/ERD.md "시스템 기능 system_feature").
 *
 * <p>표준 요청서·해결 사례가 "어느 기능 이야기인지" 가리킬 때 쓴다.
 * 기능 코드(code)는 시스템마다 따로 매기므로, 중복 불가는 (시스템, 기능 코드) 조합이다.
 */
@Entity
@Table(name = "system_feature",
        uniqueConstraints = @UniqueConstraint(name = "uk_system_feature_system_code", columnNames = {"it_system_id", "code"}))
public class SystemFeature extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "it_system_id", nullable = false)
    private ItSystem itSystem;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    protected SystemFeature() {
    }

    public SystemFeature(ItSystem itSystem, String code, String name, String description) {
        this.itSystem = itSystem;
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public ItSystem getItSystem() {
        return itSystem;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}
