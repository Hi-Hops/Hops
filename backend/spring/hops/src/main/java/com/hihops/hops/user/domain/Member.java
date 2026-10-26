package com.hihops.hops.user.domain;

import java.util.ArrayList;
import java.util.List;

import com.hihops.hops.department.domain.Department;
import com.hihops.hops.global.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * 회원. 로그인 계정과 소속 부서를 관리한다 (docs/ERD_설명.md "회원 member").
 *
 * <p>로그인 ID(loginId)와 사번(employeeNo)은 별도 컬럼이다. 사번은 JWT employeeNo 클레임의 원본이다.
 * 비밀번호는 원문을 저장하지 않고 BCrypt 해시(passwordHash)만 보관한다.
 */
@Entity
@Table(name = "member")
public class Member extends BaseEntity {

    @Column(name = "login_id", nullable = false, unique = true, length = 100)
    private String loginId;

    @Column(name = "employee_no", nullable = false, unique = true, length = 100)
    private String employeeNo;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    // LAZY: 로그인 확인처럼 부서가 필요 없는 조회에서는 부서 테이블을 함께 읽지 않는다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    // 역할 부여 목록. member_role.member_id로 연결된다(이 쪽은 읽기 전용, 저장은 MemberRole이 한다).
    @OneToMany(mappedBy = "member")
    private List<MemberRole> memberRoles = new ArrayList<>();

    protected Member() {
    }

    public Member(String loginId, String employeeNo, String passwordHash, String name, Department department) {
        this.loginId = loginId;
        this.employeeNo = employeeNo;
        this.passwordHash = passwordHash;
        this.name = name;
        this.department = department;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getEmployeeNo() {
        return employeeNo;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public Department getDepartment() {
        return department;
    }

    /** 현재 유효한 역할. 회수된(deleted_at이 있는) 역할은 빼고, 항상 같은 순서(Role 선언 순)로 돌려준다. */
    public List<Role> getRoles() {
        return memberRoles.stream()
                .filter(memberRole -> !memberRole.isDeleted())
                .map(MemberRole::getRole)
                .distinct()
                .sorted()
                .toList();
    }
}
