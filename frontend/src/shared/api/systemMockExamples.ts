export type MockExampleStage =
  | 'PROGRESS'
  | 'WAITING_CONFIRMATION'
  | 'DRAFT_READY'
  | 'INDEX_FAILED'
  | 'PUBLISHED'
  | 'GENERATION_FAILED'
  | 'NO_REUSABLE_CASE';

// 로컬 시연용 합성 시나리오. 운영 데이터나 모델 품질 평가 자료가 아니다.
export const mockExamples: { title: string; systemIndex: 0 | 1 | 2; stage: MockExampleStage }[] = [
  { title: '[예시] 공통 로그인 세션 오류 조사', systemIndex: 1, stage: 'PROGRESS' },
  {
    title: '[예시] 문서 열기 복구 · 해결 확인 대기',
    systemIndex: 0,
    stage: 'WAITING_CONFIRMATION',
  },
  { title: '[예시] 전자결재 첨부파일 복구 · 사례 초안', systemIndex: 0, stage: 'DRAFT_READY' },
  { title: '[예시] 대용량 첨부 처리 · 색인 부분 실패', systemIndex: 0, stage: 'INDEX_FAILED' },
  { title: '[예시] 공통 로그인 만료 처리 · 승인 완료', systemIndex: 1, stage: 'PUBLISHED' },
  { title: '[예시] 일괄 다운로드 기능 · 구현 검증', systemIndex: 0, stage: 'DRAFT_READY' },
  { title: '[예시] 접근 정책 복구 · 초안 생성 실패', systemIndex: 0, stage: 'GENERATION_FAILED' },
  { title: '[예시] 파일 공유 권한 처리 중', systemIndex: 2, stage: 'PROGRESS' },
  { title: '[예시] 파일 공유 접근 복구 · 사례 초안', systemIndex: 2, stage: 'DRAFT_READY' },
  { title: '[예시] 단순 설정 복구 · 재사용 사례 없음', systemIndex: 0, stage: 'NO_REUSABLE_CASE' },
];
