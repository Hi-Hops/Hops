"""승인 원문을 손실 없이 이어 붙일 수 있는 토큰 제한 청크로 나눈다."""

import tiktoken


def case_chunks(content: str, model: str, max_tokens: int = 2000) -> list[str]:
    if not content or not 4 <= max_tokens <= 2000:
        raise ValueError("유효한 원문과 청크 상한이 필요합니다")
    try:
        encoding = tiktoken.encoding_for_model(model)
    except Exception:
        # 원격 색인 쓰기 전의 로컬 준비 실패다. 모델·캐시 수정 후 수동 재시도가 가능해야 한다.
        raise ValueError("선택한 임베딩 모델의 토크나이저를 준비하지 못했습니다") from None
    chunks = []
    start = 0
    while start < len(content):
        # 문자열 경계에서 자르므로 토큰 중간의 UTF-8 바이트를 잘라 손상시키지 않는다.
        end = min(len(content), start + max_tokens * 4)
        if len(encoding.encode_ordinary(content[start:end])) > max_tokens:
            low, high = start + 1, end
            while low < high:
                middle = (low + high + 1) // 2
                if len(encoding.encode_ordinary(content[start:middle])) <= max_tokens:
                    low = middle
                else:
                    high = middle - 1
            end = low
        if end < len(content):
            # 가능하면 JSON 필드·문장·줄 경계를 택한다. 원문은 trim하거나 바꾸지 않는다.
            boundaries = []
            for mark in (',"', "\\n", ". ", "。", "; "):
                index = content.rfind(mark, start + (end - start) // 2, end)
                if index >= 0:
                    boundaries.append(index + (1 if mark == ',"' else len(mark)))
            boundary = max(boundaries, default=-1)
            if boundary > start:
                end = boundary
        while end > start and len(encoding.encode_ordinary(content[start:end])) > max_tokens:
            end -= 1
        chunk = content[start:end]
        if not chunk or len(encoding.encode_ordinary(chunk)) > max_tokens:
            raise ValueError("임베딩 청크의 토큰 상한을 확인하지 못했습니다")
        chunks.append(chunk)
        start = end
    if "".join(chunks) != content:
        raise ValueError("청크 원문이 일치하지 않습니다")
    return chunks
