"""하네스가 요청에 싣는 가상 시스템 목록. 값은 DB seed와 같다(docs/knowledge/VIRTUAL_COMPANY.md).

시스템 ID와 기능 ID는 seed와 같은 값을 쓴다. 새 시스템을 추가하면 이 목록도 같은 값으로 맞춘다.
"""

from typing import Any

SYSTEMS: tuple[dict[str, Any], ...] = (
    {
        "id": "3320ce84-c47c-592c-b599-6e968e12b0a3",
        "code": "ELECTRONIC_APPROVAL",
        "name": "전자결재",
        "features": [
            {"id": "8c3ade64-251a-5b46-ba24-7bacb5384900", "name": "문서 작성"},
            {"id": "5b540d0d-ef38-58b3-bfe6-9cfb5b20109e", "name": "문서 조회"},
            {"id": "74c8f41e-e3a1-5828-a317-2ccca15895c7", "name": "결재 상태"},
            {"id": "1da6defb-2244-5cc5-ae70-bc75d8c5d4d4", "name": "첨부 열람"},
        ],
    },
    {
        "id": "8ae35be5-1857-58c0-a478-e05b4217ff95",
        "code": "COMMON_LOGIN",
        "name": "공통 로그인",
        "features": [
            {"id": "213718e2-a478-56c0-a8ad-456b3607516c", "name": "로그인·SSO"},
            {"id": "0da3984c-7a69-5337-ba51-7b282f5f2659", "name": "계정 접근"},
            {"id": "7a361da4-7df4-592d-845f-75829318cd7c", "name": "권한 신청"},
        ],
    },
    {
        "id": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4",
        "code": "FILE_SHARING",
        "name": "파일 공유",
        "features": [
            {"id": "152e8439-1e01-5e26-9749-f77df1cb65fc", "name": "파일 열람"},
            {"id": "06bb5aba-b278-5cc1-9e5b-41c38e674991", "name": "파일 공유"},
            {"id": "b7b05865-02c9-5e39-a22e-d103b2a00057", "name": "접근 권한"},
        ],
    },
)

SYSTEM_NAME_BY_CODE = {system["code"]: system["name"] for system in SYSTEMS}
