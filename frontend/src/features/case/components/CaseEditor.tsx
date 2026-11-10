import { useState } from 'react';
import { createPortal } from 'react-dom';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { getResolution, getSystemTicket } from '@/features/ticket';
import { useSystemCommand } from '@/shared/api/useSystemCommand';
import { useFormDraft } from '@/shared/lib/useFormDraft';
import { contentError } from '@/shared/lib/content';
import { Button } from '@/shared/ui/Button';
import { FormField } from '@/shared/ui/FormField';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { ErrorState } from '@/shared/ui/ErrorState';
import type { CaseDetail } from '@/shared/api/types/hopsSystem1';
import { approveCaseCommand, editCaseCommand } from '../api/caseApi';
import { searchableCase } from '../model/publication';
import { CaseIndexStatus } from './CaseIndexStatus';
import styles from './Cases.module.css';

type CaseEditorProps = { detail: CaseDetail; reviewTarget?: HTMLElement | undefined };
export function CaseEditor({ detail, reviewTarget }: CaseEditorProps) {
  const user = useSession((s) => s.user);
  const version = detail.version;
  const actor = user?.id ?? '';
  const allowed = Boolean(user?.primarySystemIds.includes(version.systemId));
  const editable =
    allowed &&
    version.state === 'DRAFT' &&
    ['RESOLUTION', 'IMPLEMENTATION'].includes(version.caseType);
  const save = useSystemCommand(actor, 'case-edit:' + version.id);
  const approve = useSystemCommand(actor, 'case-approve:' + version.id);
  const [reviewedVersion, setReviewedVersion] = useState<number | null>(null);
  const reviewed = reviewedVersion === version.caseVersion;
  const savedValues: Record<string, string> = {
    title: version.title,
    problemStatement: version.problemStatement,
    action: version.action,
    verification: version.verification,
    applicability: version.applicability,
    limitations: version.limitations.join('\n'),
    appliesToSystemVersion: version.appliesToSystemVersion ?? '',
    accessScope: version.accessScope,
  };
  const draft = useFormDraft(actor + ':case:' + version.id, version.caseVersion, savedValues);
  const source = useQuery({
    queryKey: ['hops-system-1', 'case-source', actor, version.ticketId],
    queryFn: ({ signal }) => getSystemTicket(version.ticketId, signal),
    enabled: allowed,
    refetchInterval: 5000,
  });
  const resolution = useQuery({
    queryKey: ['hops-system-1', 'resolution', actor, version.resolutionRequestId],
    queryFn: ({ signal }) => getResolution(version.resolutionRequestId, signal),
    enabled: allowed,
    refetchInterval: 5000,
  });
  const record = source.data?.workRecords.find((item) => item.id === version.workRecordId);
  const matchingSource =
    source.data?.id === version.ticketId &&
    source.data.problemId === version.problemId &&
    record?.ticketId === version.ticketId &&
    resolution.data?.source === 'WORK_RECORD' &&
    resolution.data.sourceVersion === 1 &&
    resolution.data.sourceId === version.workRecordId &&
    resolution.data.ticketId === version.ticketId &&
    resolution.data.problemId === version.problemId &&
    ((version.caseType === 'RESOLUTION' && record.kind === 'VERIFIED_RESOLUTION') ||
      (version.caseType === 'IMPLEMENTATION' && record.kind === 'IMPLEMENTED_VERIFIED'));
  const confirmed =
    source.isSuccess &&
    resolution.isSuccess &&
    matchingSource &&
    resolution.data.state === 'RESOLVED';
  const values = editable ? draft.values : savedValues;
  const invalid =
    ['title', 'problemStatement', 'action', 'verification', 'applicability'].some((key) =>
      Boolean(contentError(values[key] ?? '')),
    ) ||
    ['limitations', 'appliesToSystemVersion'].some((key) =>
      Boolean(values[key] && contentError(values[key])),
    ) ||
    !['EMPLOYEE', 'SYSTEM_STAFF'].includes(values.accessScope ?? '');
  const locked = !editable || save.locked || approve.locked;
  const published = searchableCase(detail);
  const formId = 'case-form-' + version.id;
  const review = (
    <div className={styles.review}>
      <section className={styles.panel}>
        <h2>{editable ? '승인 전 확인' : '승인·검색 반영 상태'}</h2>
        <StatusBadge
          label={confirmed ? '문의자 해결 확인됨' : '문의자 해결 확인 전'}
          tone={confirmed ? 'success' : 'attention'}
        />
        <dl>
          <dt>검토자</dt>
          <dd>{user?.name} · 해당 시스템 주담당자</dd>
          <dt>대상 문서</dt>
          <dd>사례 문서 v{version.caseVersion}</dd>
        </dl>
        {(source.isError || resolution.isError) && (
          <ErrorState
            title="원 기록과 해결 확인을 조회하지 못했어요"
            onRetry={() => {
              void source.refetch();
              void resolution.refetch();
            }}
          />
        )}
        {source.isSuccess && resolution.isSuccess && !matchingSource && (
          <p role="alert">
            원 문의 처리 기록과 확인 요청의 연결을 검증하지 못했어요. 승인할 수 없어요.
          </p>
        )}
        {editable && (
          <>
            <Button
              form={formId}
              type="submit"
              variant="secondary"
              isLoading={save.busy}
              disabled={
                save.expired ||
                save.remaining > 0 ||
                approve.locked ||
                (!save.pending && (invalid || draft.stale || !draft.dirty))
              }
            >
              {save.busy && <span className={styles.spinner} aria-hidden="true" />}
              {save.busy ? '초안 저장 중…' : (save.label ?? '초안 수정 저장')}
            </Button>
            {save.notice && !draft.dirty && (
              <p className={styles.completed} role="status">
                초안을 저장했어요
              </p>
            )}
            <label className={styles.check}>
              <input
                type="checkbox"
                checked={reviewed}
                disabled={save.locked || approve.locked}
                onChange={(e) => {
                  setReviewedVersion(e.target.checked ? version.caseVersion : null);
                }}
              />
              원문·처리 내용·동작 확인·적용 조건·공개 범위와 민감정보를 확인했어요
            </label>
            <Button
              isLoading={approve.busy}
              disabled={
                approve.expired ||
                approve.remaining > 0 ||
                save.locked ||
                (!approve.pending && (!confirmed || !reviewed || draft.dirty || draft.stale))
              }
              onClick={() => {
                void approve.execute((requestId) =>
                  approveCaseCommand(version.id, {
                    requestId,
                    expectedCaseVersion: version.caseVersion,
                  }),
                );
              }}
            >
              {approve.busy && <span className={styles.spinner} aria-hidden="true" />}
              {approve.busy ? '사례 승인 중…' : (approve.label ?? '확인한 버전 승인')}
            </Button>
            {draft.dirty && (
              <p className={styles.hint}>작성 중인 수정 내용을 먼저 저장해 주세요.</p>
            )}
          </>
        )}
        {approve.error && <p role="alert">{approve.error}</p>}
        {version.state === 'APPROVED' && (
          <p className={styles.approved} role="status">
            사례 승인 완료
          </p>
        )}
        {approve.remaining > 0 && (
          <p role="status">{approve.remaining}초 후 다시 요청할 수 있어요.</p>
        )}
        {!editable && approve.pending && (
          <Button
            variant="secondary"
            disabled={approve.busy || approve.expired || approve.remaining > 0}
            onClick={() => {
              void approve.execute((requestId) =>
                approveCaseCommand(version.id, {
                  requestId,
                  expectedCaseVersion: version.caseVersion,
                }),
              );
            }}
          >
            {approve.label ?? '같은 승인 요청 결과 확인'}
          </Button>
        )}
        {!editable && save.pending && (
          <Button
            form={formId}
            type="submit"
            variant="secondary"
            disabled={save.busy || save.expired || save.remaining > 0}
          >
            {save.label ?? '같은 저장 요청 결과 확인'}
          </Button>
        )}
      </section>
      {version.state === 'APPROVED' && (
        <>
          <section className={styles.publication} data-complete={published} role="status">
            <div className={styles.publicationHeading}>
              <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
                <circle cx="10" cy="10" r="8" stroke="currentColor" strokeWidth="1.5" />
                <path className={styles.publicationWait} d="M6 10h8" stroke="currentColor" />
                <path
                  className={styles.publicationCheck}
                  d="m6 10 2.5 2.5L14 7"
                  stroke="currentColor"
                  strokeWidth="1.75"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
              <strong>{published ? '검색 공개 완료' : '검색 공개 전'}</strong>
              <span>승인 v{version.caseVersion}</span>
            </div>
            <p>
              {published
                ? 'Vector DB와 Neo4j에 승인 버전을 반영했어요.'
                : '두 저장소에 승인 버전이 모두 반영되면 검색에 공개돼요.'}
            </p>
          </section>
          <CaseIndexStatus detail={detail} store="VECTOR" allowed={allowed} />
          <CaseIndexStatus detail={detail} store="NEO4J" allowed={allowed} />
        </>
      )}
    </div>
  );
  return (
    <div className={styles.detailGrid}>
      <form
        id={formId}
        className={styles.editor}
        onSubmit={(event) => {
          event.preventDefault();
          if (!allowed || (!save.pending && (!editable || invalid || draft.stale))) return;
          void save.execute(
            (requestId) =>
              editCaseCommand(version.id, {
                requestId,
                expectedCaseVersion: draft.version,
                title: values.title ?? '',
                problemStatement: values.problemStatement ?? '',
                action: values.action ?? '',
                verification: values.verification ?? '',
                applicability: values.applicability ?? '',
                limitations: (values.limitations ?? '')
                  .split('\n')
                  .filter((line) => line.trim() !== ''),
                appliesToSystemVersion:
                  values.appliesToSystemVersion === ''
                    ? null
                    : (values.appliesToSystemVersion ?? null),
                accessScope: values.accessScope === 'EMPLOYEE' ? 'EMPLOYEE' : 'SYSTEM_STAFF',
              }),
            draft.clear,
          );
        }}
      >
        <StatusBadge
          label={
            version.state === 'DRAFT'
              ? 'Curator 초안·검토 필요'
              : version.state === 'APPROVED'
                ? '승인된 사례·읽기 전용'
                : '사례 상태 확인 필요'
          }
          tone={version.state === 'DRAFT' ? 'attention' : 'neutral'}
        />
        <fieldset disabled={locked}>
          <FormField id="case-title" label="사례 제목">
            <input
              id="case-title"
              value={values.title ?? ''}
              onChange={(e) => {
                draft.change('title', e.target.value);
              }}
            />
          </FormField>
          <FormField id="case-problem" label="문제 설명">
            <textarea
              id="case-problem"
              value={values.problemStatement ?? ''}
              onChange={(e) => {
                draft.change('problemStatement', e.target.value);
              }}
            />
          </FormField>
          <FormField id="case-action" label="조치 내용">
            <textarea
              id="case-action"
              value={values.action ?? ''}
              onChange={(e) => {
                draft.change('action', e.target.value);
              }}
            />
          </FormField>
          <FormField id="case-verification" label="동작 확인 결과">
            <textarea
              id="case-verification"
              value={values.verification ?? ''}
              onChange={(e) => {
                draft.change('verification', e.target.value);
              }}
            />
          </FormField>
          <FormField id="case-applicability" label="적용 조건">
            <textarea
              id="case-applicability"
              value={values.applicability ?? ''}
              onChange={(e) => {
                draft.change('applicability', e.target.value);
              }}
            />
          </FormField>
          <FormField id="case-limitations" label="한계 (한 줄에 하나)">
            <textarea
              id="case-limitations"
              value={values.limitations ?? ''}
              onChange={(e) => {
                draft.change('limitations', e.target.value);
              }}
            />
          </FormField>
          <FormField id="case-system-version" label="적용 시스템 버전 (선택)">
            <input
              id="case-system-version"
              value={values.appliesToSystemVersion ?? ''}
              placeholder="미확인 · 모르면 비워 두세요"
              onChange={(e) => {
                draft.change('appliesToSystemVersion', e.target.value);
              }}
            />
          </FormField>
          <FormField id="case-scope" label="공개 범위">
            <select
              id="case-scope"
              value={values.accessScope ?? ''}
              onChange={(e) => {
                draft.change('accessScope', e.target.value);
              }}
            >
              <option value="SYSTEM_STAFF">담당자 내부용</option>
              <option value="EMPLOYEE">직원용</option>
            </select>
          </FormField>
        </fieldset>
        <p className={styles.hint}>
          출처:{' '}
          {version.origin === 'SYNTHETIC'
            ? '가상 문의 처리 기록'
            : version.origin === 'DEMO_VERIFIED'
              ? '데모 검증 기록'
              : version.origin === 'OPERATIONAL'
                ? '운영 기록'
                : '출처 확인 필요'}{' '}
          · 확인된 원인: {record?.confirmedCause ?? version.confirmedCause ?? '미확인'}
        </p>
        {draft.stale && (
          <div className={styles.warning} role="alert">
            사례 버전이 변경됐어요. 최신 내용과 작성 중인 내용을 비교해 주세요.
            <details>
              <summary>최신 저장된 내용 보기 · v{version.caseVersion}</summary>
              <dl>
                <dt>제목</dt>
                <dd>{version.title}</dd>
                <dt>문제 설명</dt>
                <dd>{version.problemStatement}</dd>
                <dt>조치</dt>
                <dd>{version.action}</dd>
                <dt>동작 확인</dt>
                <dd>{version.verification}</dd>
                <dt>적용 조건</dt>
                <dd>{version.applicability}</dd>
                <dt>한계</dt>
                <dd>{version.limitations.join('\n') || '없음'}</dd>
                <dt>적용 버전</dt>
                <dd>{version.appliesToSystemVersion ?? '미확인'}</dd>
                <dt>공개 범위</dt>
                <dd>{version.accessScope === 'EMPLOYEE' ? '직원용' : '담당자 내부용'}</dd>
              </dl>
            </details>
            <Button
              variant="secondary"
              disabled={save.locked || approve.locked}
              onClick={draft.reviewLatest}
            >
              최신 버전 확인 후 이어쓰기
            </Button>
          </div>
        )}
        {save.error && <p role="alert">{save.error}</p>}
        {save.remaining > 0 && <p role="status">{save.remaining}초 후 다시 요청할 수 있어요.</p>}
      </form>
      {reviewTarget ? createPortal(review, reviewTarget) : review}
    </div>
  );
}
