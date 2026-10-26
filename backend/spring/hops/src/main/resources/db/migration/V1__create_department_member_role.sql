-- 계정·조직 테이블: 부서, 회원, 역할 부여 (docs/ERD_설명.md "계정 · 조직")
-- 모든 환경(로컬·공용·운영)에서 실행된다. 가상 데이터는 여기 넣지 않고 db/seed에 둔다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 V2__... 파일을 새로 추가한다.

-- 부서: 요청자 소속 부서와 IT 처리 부서
CREATE TABLE department (
    id          UUID          PRIMARY KEY DEFAULT uuidv7(),
    code        VARCHAR(30)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    -- AI가 배정할 수 있는 IT 처리 부서만 true (요청자 소속 부서는 false)
    is_routable BOOLEAN       NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uk_department_code UNIQUE (code)
);

-- 회원: 로그인 계정과 소속 부서
CREATE TABLE member (
    id            UUID          PRIMARY KEY DEFAULT uuidv7(),
    login_id      VARCHAR(100)  NOT NULL,
    -- 사번. JWT employeeNo의 원본이며 회원 ID·로그인 ID와 별개다
    employee_no   VARCHAR(100)  NOT NULL,
    -- 비밀번호 원문은 저장하지 않고 BCrypt 해시만 보관한다
    password_hash VARCHAR(255)  NOT NULL,
    name          VARCHAR(50)   NOT NULL,
    department_id UUID          NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    CONSTRAINT uk_member_login_id UNIQUE (login_id),
    CONSTRAINT uk_member_employee_no UNIQUE (employee_no),
    CONSTRAINT fk_member_department FOREIGN KEY (department_id) REFERENCES department (id)
);

-- "우리 부서 사람 목록" 조회가 잦아서 소속 부서로 찾는 인덱스를 둔다
CREATE INDEX idx_member_department_id ON member (department_id);

-- 역할 부여: 부서와 별개인 관리자 권한 (BIZ_ADMIN / AGENT_OPS_ADMIN)
CREATE TABLE member_role (
    id         UUID         PRIMARY KEY DEFAULT uuidv7(),
    member_id  UUID         NOT NULL,
    role       VARCHAR(30)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    -- 같은 사람에게 같은 역할을 두 번 주지 않는다
    CONSTRAINT uk_member_role_member_role UNIQUE (member_id, role),
    CONSTRAINT fk_member_role_member FOREIGN KEY (member_id) REFERENCES member (id)
);
