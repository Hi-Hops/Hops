"""개발용 고정 지식 자료. 모두 가상(SYNTHETIC)이며 실제 시스템의 매뉴얼이 아니다.

사용법 문의가 가이드 안내로 끝나는 흐름과 담당자 전용 자료가 직원에게 섞이지 않는 경계를
확인하기 위한 최소 자료다. 팀이 선정한 공통 데이터셋, 검색 품질 평가용 데이터, 운영 지식이
아니다. 공통 지식 데이터셋은 별도 단계에서 출처·검수와 함께 구축한다.
"""

from dataclasses import dataclass
from typing import Literal

from hops_ai.contracts.wire import AccessScope

SystemCode = str  # 개발용 자료의 시스템 코드. 값은 요청이 알려 주는 코드와 맞춘다
DocumentKind = Literal["GUIDE", "POLICY", "RUNBOOK"]


@dataclass(frozen=True)
class FixtureDocument:
    key: str
    kind: DocumentKind
    system_code: SystemCode
    access_scope: AccessScope
    title: str
    applicability: str
    body: str
    # 질의에 이 구절이 들어 있으면 일치로 센다. 사용법을 묻는 표현 위주로 좁게 둔다.
    keywords: tuple[str, ...]
    version: int = 1


FIXTURE_DOCUMENTS: tuple[FixtureDocument, ...] = (
    FixtureDocument(
        key="guide-approval-document-lookup",
        kind="GUIDE",
        system_code="ELECTRONIC_APPROVAL",
        access_scope="EMPLOYEE",
        title="전자결재 문서 조회 방법",
        applicability="개발용 고정 가이드. 전자결재의 문서 조회 화면 사용법에 적용한다.",
        body=(
            "전자결재에서 문서를 찾는 방법입니다.\n"
            "1. 전자결재 메뉴에서 '문서 조회'를 선택합니다.\n"
            "2. 문서 상태와 조회 기간을 지정합니다. "
            "조회 기간에 문서 작성일이 포함되어야 목록에 표시됩니다.\n"
            "3. 제목이나 작성자를 입력해 목록을 좁힙니다.\n"
            "본인이 열람할 수 있는 문서만 표시됩니다."
        ),
        keywords=("문서 조회", "조회 방법", "조회하는", "찾는 방법", "찾으려면", "어떻게 찾"),
    ),
    FixtureDocument(
        key="guide-login-system-access-request",
        kind="GUIDE",
        system_code="COMMON_LOGIN",
        access_scope="EMPLOYEE",
        title="시스템 이용 권한 신청 방법",
        applicability="개발용 고정 가이드. 공통 로그인의 시스템 접근 신청 절차에 적용한다.",
        body=(
            "다른 시스템의 이용 권한을 신청하는 방법입니다.\n"
            "1. 공통 로그인에 로그인한 뒤 '접근 신청'을 선택합니다.\n"
            "2. 이용할 시스템과 업무 목적을 입력합니다.\n"
            "3. 신청을 제출하면 시스템 담당자의 승인 결과가 알림으로 전달됩니다.\n"
            "승인 전에는 해당 시스템을 이용할 수 없습니다."
        ),
        keywords=("접근 신청", "권한 신청", "이용 권한", "권한을 신청", "신청 방법", "신청하는"),
    ),
    FixtureDocument(
        key="guide-file-sharing-upload-and-share",
        kind="GUIDE",
        system_code="FILE_SHARING",
        access_scope="EMPLOYEE",
        title="파일 올리기와 공유 방법",
        applicability="개발용 고정 가이드. 파일 공유의 업로드와 공유 대상 지정에 적용한다.",
        body=(
            "파일을 올리고 동료와 공유하는 방법입니다.\n"
            "1. 파일 공유에서 폴더를 선택하고 '업로드'로 파일을 올립니다.\n"
            "2. 올린 파일에서 '공유'를 선택하고 공유할 사람과 읽기 또는 편집 범위를 지정합니다.\n"
            "공유 범위를 넓히려면 해당 폴더 소유자의 허용이 필요합니다."
        ),
        keywords=("공유 방법", "파일 올리", "업로드", "공유하는", "공유하려면", "어떻게 공유"),
    ),
    FixtureDocument(
        key="runbook-approval-missing-document",
        kind="RUNBOOK",
        system_code="ELECTRONIC_APPROVAL",
        access_scope="SYSTEM_STAFF",
        title="전자결재 문서 미표시 점검 순서(담당자용)",
        applicability="개발용 고정 런북. 전자결재 담당자의 문서 미표시 점검에만 적용한다.",
        body=(
            "문서가 목록에 보이지 않는다는 문의의 담당자 점검 순서입니다.\n"
            "1. 문의자의 조회 기간과 문서 작성일을 대조합니다.\n"
            "2. 문서 상태(임시저장·상신)와 열람 권한을 확인합니다.\n"
            "3. 위 항목이 정상이면 조회 색인 누락 여부를 점검합니다."
        ),
        keywords=("문서 조회", "미표시", "보이지 않", "안 보여", "조회 색인", "점검"),
    ),
)
