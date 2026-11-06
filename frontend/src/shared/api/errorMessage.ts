import { ApiError } from './client';

const inputLabels: Record<string, string> = {
  loginId: '아이디',
  password: '비밀번호',
  content: '내용',
  reportedSystemId: '보고 시스템',
  systemId: '보고 시스템',
  problemIds: '보완 대상',
  action: '처리 내용',
  verification: '동작 확인 결과',
  confirmedCause: '확인된 원인',
  appliesToSystemVersion: '적용 시스템 버전',
  reason: '정정 사유',
  evidenceNote: '정정 근거',
  responsibleSystemId: '책임 시스템',
  processingType: '처리 유형',
  title: '사례 제목',
  problemStatement: '문제 설명',
  applicability: '적용 조건',
  limitations: '한계',
  accessScope: '공개 범위',
};

/** 서버 내부 필드 대신 현재 화면에서 수정할 수 있는 입력 항목만 안내한다. */
export function apiErrorMessage(error: unknown, fallback: string) {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : fallback;
  const fields = [
    ...new Set(
      error.body.fieldErrors.flatMap(({ field }) =>
        Object.hasOwn(inputLabels, field) ? [inputLabels[field]] : [],
      ),
    ),
  ];
  return `${error.message}${fields.length ? ` ${fields.join('·')} 입력을 확인해 주세요.` : ''}`;
}
