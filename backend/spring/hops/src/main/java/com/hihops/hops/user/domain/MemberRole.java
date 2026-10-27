package com.hihops.hops.user.domain;

import com.hihops.hops.global.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 역할 부여 한 건 (docs/ERD_설명.md "역할 부여 member_role"). 한 회원에게 여러 역할을 줄 수 있다.
 *
 * <p>역할을 회수할 때는 행을 지우지 않고 deleted_at만 기록한다. 회수된 역할은 토큰에 넣지 않는다.
 */
@Entity
@Table(name = "member_role")
public class MemberRole extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    // 숫자(0, 1)가 아닌 이름("BIZ_ADMIN")으로 저장한다. 순서를 바꿔도 DB 값이 꼬이지 않는다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    protected MemberRole() {
    }

    public MemberRole(Member member, Role role) {
        this.member = member;
        this.role = role;
    }

    public Role getRole() {
        return role;
    }
}
