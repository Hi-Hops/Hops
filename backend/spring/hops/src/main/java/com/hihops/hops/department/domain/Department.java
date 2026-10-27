package com.hihops.hops.department.domain;

import com.hihops.hops.global.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 부서. 요청자 소속 부서와 IT 처리 부서를 함께 관리한다 (docs/ERD_설명.md "부서 department").
 *
 * <p>routable이 true인 부서만 AI가 티켓을 배정할 수 있는 IT 처리 부서다 (요청자 소속 부서는 false).
 * code는 AI 제안·JWT departmentCode에 쓰는 고유 코드이고, name은 화면 표시용이다.
 */
@Entity
@Table(name = "department")
public class Department extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "is_routable", nullable = false)
    private boolean routable;

    protected Department() {
    }

    public Department(String code, String name, boolean routable) {
        this.code = code;
        this.name = name;
        this.routable = routable;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isRoutable() {
        return routable;
    }
}
