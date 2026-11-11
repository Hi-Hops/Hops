import { useState } from 'react';
import { useSession } from '@/features/auth';
import { useSystemCommand } from '@/shared/api/useSystemCommand';
import { useFormDraft } from '@/shared/lib/useFormDraft';
import { contentError } from '@/shared/lib/content';
import { Button } from '@/shared/ui/Button';
import { FormField } from '@/shared/ui/FormField';
import type { TicketDetail } from '@/shared/api/types/hopsSystem1';
import { workRecordCommand } from '../api/systemTicketApi';
import {
  isWorkKind,
  mutableTicket,
  nullableInput,
  workKinds,
  processingLabels,
} from '../model/workflow';
import styles from './Workflow.module.css';

type WorkRecordEditorProps = { ticket: TicketDetail };
export function WorkRecordEditor({ ticket }: WorkRecordEditorProps) {
  const actor = useSession((s) => s.user);
  const command = useSystemCommand(actor?.id ?? '', 'work-record:' + ticket.id);
  const draft = useFormDraft((actor?.id ?? '') + ':work:' + ticket.id, ticket.ticketRevision, {
    action: '',
    verification: '',
    kind: 'PROGRESS',
    confirmedCause: '',
    appliesToSystemVersion: '',
  });
  const values = draft.values;
  const kind = values.kind ?? '';
  const verified = kind === 'VERIFIED_RESOLUTION' || kind === 'IMPLEMENTED_VERIFIED';
  const owner =
    actor?.id === ticket.currentPrimary.id &&
    actor.primarySystemIds.includes(ticket.responsibleSystem.id);
  const permitted =
    owner &&
    (command.pending ||
      (mutableTicket(ticket) &&
        ticket.state !== 'WAITING_CONFIRMATION' &&
        Object.hasOwn(processingLabels, ticket.processingType)));
  const completionKind =
    ticket.processingType === 'CHANGE_REQUEST' ? 'IMPLEMENTED_VERIFIED' : 'VERIFIED_RESOLUTION';
  const kindAllowed = isWorkKind(kind) && (!verified || kind === completionKind);
  const saveLabel = verified
    ? '저장하고 해결 확인 요청'
    : kind === 'FAILED_ATTEMPT'
      ? '시도 기록 저장'
      : '진행 기록 저장';
  const [additionalOpen, setAdditionalOpen] = useState(
    [values.appliesToSystemVersion, values.confirmedCause].some(Boolean),
  );
  // 숨긴 항목은 유형을 다시 바꿀 때를 위해 초안에만 두고 검사·저장하지 않는다.
  const shownKeys = verified
    ? ['action', 'verification', 'confirmedCause', 'appliesToSystemVersion']
    : ['action'];
  const invalid =
    Boolean(contentError(values.action ?? '')) ||
    (verified && Boolean(contentError(values.verification ?? ''))) ||
    shownKeys.some((key) => Boolean(values[key] && contentError(values[key])));
  const completedField = (key: string) => (verified ? nullableInput(values[key] ?? '') : null);
  if (ticket.state === 'WAITING_CONFIRMATION' && !permitted) return null;
  return (
    <section className={styles.panel} aria-label="문의 처리 기록 입력">
      <h2>문의 처리 기록</h2>
      {!permitted ? (
        <p role="status">현재 주담당자만 미완료 티켓에 기록할 수 있어요.</p>
      ) : (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();
            if (!command.pending && (invalid || !kindAllowed || draft.stale)) return;
            if (!isWorkKind(kind)) return;
            void command.execute(
              (requestId) =>
                workRecordCommand(ticket.id, {
                  requestId,
                  expectedTicketRevision: draft.version,
                  kind,
                  action: values.action ?? '',
                  verification: completedField('verification'),
                  confirmedCause: completedField('confirmedCause'),
                  appliesToSystemVersion: completedField('appliesToSystemVersion'),
                }),
              draft.clear,
            );
          }}
        >
          <fieldset disabled={command.locked}>
            <fieldset className={styles.kindField}>
              <legend>기록 유형</legend>
              <div className={styles.kindOptions}>
                {(['PROGRESS', 'FAILED_ATTEMPT', completionKind] as const).map((value) => (
                  <label key={value}>
                    <input
                      type="radio"
                      name="work-kind"
                      value={value}
                      checked={kind === value}
                      onChange={() => {
                        draft.change('kind', value);
                      }}
                    />
                    <span>{workKinds[value]}</span>
                  </label>
                ))}
              </div>
            </fieldset>
            <FormField
              id="work-action"
              label="처리 내용"
              hint={
                verified
                  ? '문의자 해결 확인 화면에 그대로 보여요. 다른 담당자도 따라 할 수 있게 무엇을, 어디서, 어떻게 했는지 적어 주세요.'
                  : kind === 'FAILED_ATTEMPT'
                    ? '시도한 조치와 그 결과를 적어 주세요. 내용은 담당자 처리 이력에 남아요.'
                    : '지금까지 확인한 내용을 적어 주세요. 내용은 담당자 처리 이력에 남아요.'
              }
            >
              <textarea
                id="work-action"
                value={values.action ?? ''}
                placeholder={
                  verified
                    ? '예: 관리자 화면에서 첨부 저장 경로를 다시 지정하고 서비스를 재시작했어요.'
                    : kind === 'FAILED_ATTEMPT'
                      ? '예: 조회 기간을 넓혀 다시 검색했지만 문서가 보이지 않았어요.'
                      : '예: 문서 조회 화면에서 현재 조회 조건을 확인하고 있어요.'
                }
                onChange={(e) => {
                  draft.change('action', e.target.value);
                }}
              />
            </FormField>
            {verified && (
              <>
                <FormField
                  id="work-verification"
                  label="동작 확인 결과 (필수)"
                  hint="어떤 조건에서 무엇을 해 봤는지와 그 결과를 적어 주세요."
                >
                  <textarea
                    id="work-verification"
                    value={values.verification ?? ''}
                    placeholder="예: 테스트 계정으로 같은 문서를 열어 첨부파일이 표시되는 것을 확인했어요."
                    onChange={(e) => {
                      draft.change('verification', e.target.value);
                    }}
                  />
                </FormField>
                <details
                  className={styles.more}
                  open={additionalOpen}
                  onToggle={(e) => {
                    setAdditionalOpen(e.currentTarget.open);
                  }}
                >
                  <summary>추가 정보</summary>
                  <FormField
                    id="work-cause"
                    label="확인된 원인 (선택)"
                    hint="확인하지 못한 원인은 비워 두세요. 추정을 확정 원인으로 입력하지 않아요."
                  >
                    <input
                      id="work-cause"
                      value={values.confirmedCause ?? ''}
                      onChange={(e) => {
                        draft.change('confirmedCause', e.target.value);
                      }}
                    />
                  </FormField>
                  <FormField id="work-version" label="적용 시스템 버전 (선택)">
                    <input
                      id="work-version"
                      value={values.appliesToSystemVersion ?? ''}
                      placeholder="모르면 비워 두세요"
                      onChange={(e) => {
                        draft.change('appliesToSystemVersion', e.target.value);
                      }}
                    />
                  </FormField>
                </details>
              </>
            )}
          </fieldset>
          {verified && kindAllowed && Boolean(values.action?.trim()) && (
            <details className={styles.preview}>
              <summary>저장 전 미리보기</summary>
              <div>
                <strong>문의자 해결 확인 화면</strong>
                <p>{values.action}</p>
              </div>
              <div>
                <strong>해결 사례 초안</strong>
                <dl>
                  <dt>처리 내용</dt>
                  <dd>{values.action}</dd>
                  <dt>동작 확인 결과</dt>
                  <dd>{values.verification?.trim() ? values.verification : '입력 필요'}</dd>
                  <dt>확인된 원인</dt>
                  <dd>{values.confirmedCause?.trim() ? values.confirmedCause : '미확인'}</dd>
                  <dt>적용 시스템 버전</dt>
                  <dd>
                    {values.appliesToSystemVersion?.trim()
                      ? values.appliesToSystemVersion
                      : '미확인'}
                  </dd>
                </dl>
              </div>
            </details>
          )}
          {shownKeys.some((key) => Boolean(values[key] && contentError(values[key]))) && (
            <p role="alert">
              입력은 공백만이 아닌 1~4,000자로 작성해 주세요. 입력 내용은 그대로 보존해요.
            </p>
          )}
          {draft.stale && (
            <div role="alert" className={styles.warning}>
              티켓이 변경됐어요. 최신 정보와 작성 내용을 비교한 뒤 이어서 저장해 주세요.
              <Button variant="secondary" disabled={command.locked} onClick={draft.reviewLatest}>
                최신 티켓 확인 후 이어쓰기
              </Button>
            </div>
          )}
          <Button
            type="submit"
            disabled={
              command.busy ||
              command.expired ||
              command.remaining > 0 ||
              (!command.pending && (invalid || !kindAllowed || draft.stale))
            }
          >
            {command.label ?? saveLabel}
          </Button>
          {ticket.state === 'WAITING' && !verified && (
            <p className={styles.hint}>저장하면 문의자에게 ‘처리 중’으로 표시돼요.</p>
          )}
          {command.error && <p role="alert">{command.error}</p>}
          {command.notice && <p role="status">{command.notice}</p>}
          {command.remaining > 0 && (
            <p role="status">{command.remaining}초 후 다시 요청할 수 있어요.</p>
          )}
        </form>
      )}
    </section>
  );
}
