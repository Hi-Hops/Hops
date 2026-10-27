# IT 용어집 v0

JSON 정본은 `datasets/knowledge/v0/catalogs/it_glossary.json`이다. 이 문서는 팀 검토용 자동 생성본이므로 직접 수정하지 않는다.

- 전체 50건
- 내용 검수: 데이터 담당자 확정
- 팀 상태: 리뷰 대기
- 공개 등급: 정의만 `EMPLOYEE/QUOTABLE`; 운영 명령·원시 로그·내부 설정은 포함하지 않는다.
- RAG 적재: 팀 승인 전 `PENDING`

| ID | 용어 | 별칭 | 분류 | 정의 | 관련 컴포넌트 |
|---|---|---|---|---|---|
| TERM-001 | API | 응용 프로그램 인터페이스 | WEB | 화면이나 다른 시스템이 서버 기능과 데이터를 요청하는 약속된 통신 경로. | PORTAL_API |
| TERM-002 | HTTP 500 | 500 오류, Internal Server Error | WEB | 서버가 요청을 처리하는 중 내부 오류가 발생했음을 나타내는 HTTP 상태. 이것만으로 원인은 확정할 수 없다. | PORTAL_API, PORTAL_DB |
| TERM-003 | X-Request-Id | 요청 추적 번호, requestId | OBSERVABILITY | 사용자 응답과 서버 로그의 같은 요청 한 건을 연결하는 식별자. | PORTAL_API |
| TERM-004 | 구조화 로그 | JSON 로그 | OBSERVABILITY | 시간·요청 ID·경로·상태·예외 같은 값을 필드로 나눠 기계가 검색하기 쉽게 기록한 로그. | PORTAL_API, PORTAL_DB |
| TERM-005 | 스택 지문 | stack trace fingerprint | OBSERVABILITY | 민감한 전체 스택을 노출하지 않고 같은 예외 발생 위치를 비교하기 위한 축약 식별값. | PORTAL_API |
| TERM-006 | 메트릭 | metrics | OBSERVABILITY | 요청 수·오류 수·연결 수처럼 시간에 따라 집계되는 수치형 관측값. | PORTAL_API, PORTAL_DB |
| TERM-007 | 커넥션 풀 | DB 연결 풀, connection pool | DATABASE | 애플리케이션이 DB 연결을 매번 새로 만들지 않고 재사용하도록 보관하는 연결 집합. | PORTAL_API |
| TERM-008 | HikariCP | Hikari | DATABASE | Demo Portal의 Spring 애플리케이션에서 사용하는 JDBC 커넥션 풀 구현체. | PORTAL_API |
| TERM-009 | active connection | 사용 중 연결 | DATABASE | 커넥션 풀에서 현재 애플리케이션 작업이 점유하고 있는 DB 연결. | PORTAL_API |
| TERM-010 | idle connection | 유휴 연결 | DATABASE | 풀에 있으나 현재 작업이 사용하지 않아 즉시 빌려줄 수 있는 DB 연결. | PORTAL_API |
| TERM-011 | pending connection request | 연결 대기 요청 | DATABASE | 풀에서 사용할 연결이 없어 반환을 기다리는 애플리케이션 요청. | PORTAL_API |
| TERM-012 | connectionTimeout | 연결 획득 제한시간 | DATABASE | 커넥션 풀에서 연결을 기다릴 수 있는 최대 시간. 초과 시 연결 획득 오류가 발생한다. | PORTAL_API |
| TERM-013 | maximumPoolSize | 풀 최대 크기 | DATABASE | HikariCP가 보유할 수 있는 idle과 active 연결의 최대 합계. | PORTAL_API |
| TERM-014 | leak detection | 연결 누수 탐지 | DATABASE | 연결이 설정된 시간보다 오래 풀에 반환되지 않을 때 누수 가능성을 알리는 보조 경고. 실제 반환 여부와 코드 경로를 확인하기 전 누수로 확정하지 않는다. | PORTAL_API |
| TERM-015 | max_connections | DB 최대 연결 수 | DATABASE | PostgreSQL 서버가 동시에 허용하는 최대 연결 수 설정. | PORTAL_DB |
| TERM-016 | reserved connection | 예약 연결 슬롯 | DATABASE | 일반 연결이 가득 찬 상황에서도 복구용 권한 사용자가 접속할 수 있도록 남겨 두는 PostgreSQL 연결 슬롯. | PORTAL_DB |
| TERM-017 | pg_stat_activity | 세션 활동 뷰 | DATABASE | PostgreSQL 서버 프로세스별 현재 활동·상태·대기 이벤트를 보여주는 시스템 뷰. | PORTAL_DB |
| TERM-018 | numbackends | 현재 DB 연결 수 | DATABASE | 특정 데이터베이스에 현재 연결된 backend 프로세스 수. | PORTAL_DB |
| TERM-019 | 트랜잭션 | transaction | DATABASE | 여러 DB 작업을 하나의 성공 또는 실패 단위로 처리하는 범위. | PORTAL_DB |
| TERM-020 | idle in transaction | 트랜잭션 열린 유휴 상태 | DATABASE | 트랜잭션은 끝나지 않았지만 현재 쿼리를 실행하지 않는 세션 상태. 장시간 유지되면 잠금을 보유할 수 있다. | PORTAL_DB |
| TERM-021 | Lock wait | 잠금 대기 | DATABASE | 다른 트랜잭션이 보유한 충돌 잠금이 해제될 때까지 현재 작업이 기다리는 상태. | PORTAL_DB |
| TERM-022 | Deadlock | 교착 상태 | DATABASE | 둘 이상의 트랜잭션이 서로가 가진 잠금을 기다려 진행할 수 없는 순환 대기. PostgreSQL은 하나를 중단해 해소한다. | PORTAL_DB |
| TERM-023 | blocker | 차단 세션 | DATABASE | 다른 세션이 필요한 잠금을 보유해 해당 세션을 기다리게 하는 트랜잭션 또는 세션. | PORTAL_DB |
| TERM-024 | waiter | 대기 세션 | DATABASE | 필요한 잠금을 얻지 못해 blocker가 끝나기를 기다리는 세션. | PORTAL_DB |
| TERM-025 | Slow Query | 느린 쿼리 | DATABASE | 기준 시간보다 오래 실행돼 사용자 응답 지연에 영향을 줄 수 있는 DB 작업. 한 번의 지연과 반복되는 문제를 구분해야 한다. | PORTAL_DB |
| TERM-026 | query_id | 쿼리 식별자 | DATABASE | SQL 원문을 직접 노출하지 않고 같은 형태의 PostgreSQL 쿼리를 묶어 비교하는 식별자. | PORTAL_DB |
| TERM-027 | pg_stat_statements | 쿼리 통계 확장 | DATABASE | query_id별 호출 수와 누적·평균·최대 실행시간 등을 집계하는 PostgreSQL 확장. | PORTAL_DB |
| TERM-028 | 실행계획 | query plan | DATABASE | PostgreSQL이 테이블과 인덱스를 어떤 순서와 방식으로 읽을지 정한 계획. | PORTAL_DB |
| TERM-029 | Seq Scan | 순차 스캔 | DATABASE | 테이블의 행을 넓게 순서대로 읽는 실행계획 노드. 항상 문제는 아니며 데이터량과 조건을 함께 봐야 한다. | PORTAL_DB |
| TERM-030 | Index Scan | 인덱스 스캔 | DATABASE | 인덱스를 이용해 조건에 맞는 행을 찾아가는 실행계획 노드. 사용 자체가 항상 빠른 처리를 보장하지는 않는다. | PORTAL_DB |
| TERM-031 | EXPLAIN | 없음 | DATABASE | 쿼리를 실제 실행하지 않고 PostgreSQL이 선택한 예상 실행계획을 확인하는 명령. | PORTAL_DB |
| TERM-032 | EXPLAIN ANALYZE | 없음 | DATABASE | 쿼리를 실제 실행해 실제 행 수와 시간을 포함한 실행계획을 확인하는 명령. 승인된 읽기 SELECT와 안전한 환경에서만 사람이 사용한다. | PORTAL_DB |
| TERM-033 | RAG | Retrieval-Augmented Generation, 검색 증강 생성 | AI | 질문과 관련된 승인 문서를 먼저 검색한 뒤 그 근거를 LLM에 제공해 답변을 만드는 방식. | 공통 |
| TERM-034 | 청크 | chunk | AI | 검색과 권한 검증을 위해 문서를 의미 단위로 나눈 조각. | 공통 |
| TERM-035 | 임베딩 | embedding | AI | 문장이나 문서를 의미 유사도 검색에 사용할 숫자 벡터로 변환한 값. | 공통 |
| TERM-036 | MFA | Multi-Factor Authentication, 다단계 인증 | IDENTITY | 비밀번호 외에 인증 앱·문자·보안 키 같은 추가 수단을 함께 확인하는 인증 방식. | MFA |
| TERM-037 | SSO | Single Sign-On, 통합 로그인 | IDENTITY | 한 번의 조직 인증으로 권한이 허용된 여러 사내 서비스에 접근하게 하는 로그인 방식. | SSO |
| TERM-038 | AADSTS 오류 코드 | AADSTS | IDENTITY | Microsoft Entra ID 로그인 과정에서 실패 단계와 유형을 구분하기 위해 반환하는 오류 코드. 정확한 코드별 의미를 확인해야 한다. | SSO |
| TERM-039 | VPN | Virtual Private Network, 가상 사설망 | NETWORK | 외부 네트워크에서 암호화된 연결을 통해 허용된 사내 자원에 접근하는 방식. | VPN |
| TERM-040 | Always On VPN | AOVPN | NETWORK | Windows 관리형 프로필을 사용해 조직 정책에 따라 사내망 연결을 제공하는 원격 접속 구성. | VPN |
| TERM-041 | IKEv2 | Internet Key Exchange version 2 | NETWORK | VPN 연결의 보안 협상과 키 교환에 사용하는 프로토콜. Hops 가상 기업 VPN의 표준 프로토콜이다. | VPN |
| TERM-042 | RRAS | Routing and Remote Access Service, 라우팅 및 원격 액세스 | NETWORK | Windows Server에서 VPN 연결과 원격 액세스를 제공하는 서버 역할. | VPN |
| TERM-043 | NPS | Network Policy Server, 네트워크 정책 서버 | NETWORK | VPN 같은 네트워크 접속 요청에 조직의 인증·권한 정책을 적용하는 Windows Server 역할. | VPN |
| TERM-044 | RasClient 이벤트 | RasClient | OBSERVABILITY | Windows 원격 접속 클라이언트가 VPN 연결 결과와 오류 코드를 기록하는 이벤트 로그. | VPN |
| TERM-045 | WAN Miniport | WAN 미니포트 | NETWORK | Windows가 VPN 같은 원격 접속 프로토콜을 처리할 때 사용하는 가상 네트워크 어댑터 계층. | VPN |
| TERM-046 | SMB | Server Message Block | FILE_SHARE | Windows 환경에서 네트워크를 통해 파일과 폴더를 공유할 때 사용하는 프로토콜. | SMB |
| TERM-047 | UNC 경로 | Universal Naming Convention, 네트워크 공유 경로 | FILE_SHARE | 두 개의 역슬래시로 시작해 서버 이름과 공유 이름을 나타내는 Windows 네트워크 경로 형식. | SMB |
| TERM-048 | TCP 445 | SMB 포트 445 | NETWORK | 현대 Windows SMB 파일 공유 연결에 사용하는 기본 TCP 포트. 도달 실패만으로 서버와 중간 네트워크 중 어느 쪽 문제인지 확정할 수 없다. | SMB |
| TERM-049 | 공유 권한 | share permission | FILE_SHARE | SMB 공유에 접속한 사용자나 그룹이 네트워크를 통해 수행할 수 있는 작업을 제한하는 권한. | SMB |
| TERM-050 | NTFS 권한 | NTFS permission | FILE_SHARE | Windows 파일 시스템에서 사용자나 그룹의 파일·폴더 읽기, 쓰기, 수정 같은 동작을 제한하는 권한. | SMB |
