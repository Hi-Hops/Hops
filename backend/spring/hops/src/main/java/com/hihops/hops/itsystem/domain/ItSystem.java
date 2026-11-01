package com.hihops.hops.itsystem.domain;

import com.hihops.hops.global.entity.BaseEntity;
import com.hihops.hops.user.domain.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 시스템. 문의를 받는 업무시스템(전자결재·공통 로그인·파일 공유 등)과 주담당자를 관리한다
 * (docs/ERD.md "시스템 it_system").
 *
 * <p>문의는 부서가 아니라 이 시스템의 주담당자(primaryOwner)에게 배정한다. 주담당자는 시스템당 1명이다.
 * 클래스 이름을 System으로 하지 않은 이유: java.lang.System과 이름이 겹쳐 헷갈린다.
 */
@Entity
@Table(name = "it_system")
public class ItSystem extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    // DB 타입이 TEXT라서 명시한다. 없으면 JPA가 VARCHAR(255)로 기대해 validate에서 실패한다.
    @Column(columnDefinition = "TEXT")
    private String description;

    // LAZY: 시스템 목록만 볼 때는 주담당자 회원을 함께 읽지 않는다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "primary_owner_member_id", nullable = false)
    private Member primaryOwner;

    protected ItSystem() {
    }

    public ItSystem(String code, String name, String description, Member primaryOwner) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.primaryOwner = primaryOwner;
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

    public Member getPrimaryOwner() {
        return primaryOwner;
    }
}
