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
      (mutableTicket(ticket) && Object.hasOwn(processingLabels, ticket.processingType)));
  const kindAllowed =
    isWorkKind(kind) &&
    (!verified ||
      (ticket.state !== 'WAITING_CONFIRMATION' &&
        (ticket.processingType === 'CHANGE_REQUEST'
          ? kind === 'IMPLEMENTED_VERIFIED'
          : kind === 'VERIFIED_RESOLUTION')));
  const invalid =
    Boolean(contentError(values.action ?? '')) ||
    (verified && Boolean(contentError(values.verification ?? ''))) ||
    ['verification', 'confirmedCause', 'appliesToSystemVersion'].some((key) =>
      Boolean(values[key] && contentError(values[key])),
    );
  return (
    <section className={styles.panel} aria-label="문의 처리 기록 입력">
      <h2>문의 처리 기록</h2>
      <p>
        처리한 내용과 동작 확인 결과를 기록해 주세요. 처리 완료 기록은 문의자에게 해결 확인을
        요청해요.
      </p>
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
                  verification: nullableInput(values.verification ?? ''),
                  confirmedCause: nullableInput(values.confirmedCause ?? ''),
                  appliesToSystemVersion: nullableInput(values.appliesToSystemVersion ?? ''),
                }),
              draft.clear,
            );
          }}
        >
          <fieldset disabled={command.locked}>
            <FormField id="work-action" label="처리 내용">
              <textarea
                id="work-action"
                value={values.action ?? ''}
                onChange={(e) => {
                  draft.change('action', e.target.value);
                }}
              />
            </FormField>
            <FormField
              id="work-verification"
              label={verified ? '동작 확인 결과 (필수)' : '동작 확인 결과 (선택)'}
            >
              <textarea
                id="work-verification"
                value={values.verification ?? ''}
                onChange={(e) => {
                  draft.change('verification', e.target.value);
                }}
              />
            </FormField>
            <FormField id="work-kind" label="기록 유형">
              <select
                id="work-kind"
                value={kind}
                onChange={(e) => {
                  draft.change('kind', e.target.value);
                }}
              >
                {Object.entries(workKinds).map(([value, label]) => (
                  <option
                    key={value}
                    value={value}
                    disabled={
                      (value === 'IMPLEMENTED_VERIFIED' &&
                        ticket.processingType !== 'CHANGE_REQUEST') ||
                      (value === 'VERIFIED_RESOLUTION' &&
                        ticket.processingType === 'CHANGE_REQUEST') ||
                      (ticket.state === 'WAITING_CONFIRMATION' &&
                        ['VERIFIED_RESOLUTION', 'IMPLEMENTED_VERIFIED'].includes(value))
                    }
                  >
                    {label}
                  </option>
                ))}
              </select>
            </FormField>
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
          </fieldset>
          <p className={styles.hint}>
            각 항목은 4,000자까지 작성할 수 있어요. 진행·실패 시도는 이력만 저장해요. 확인 대기
            중에는 새 처리 완료 기록으로 요청을 교체할 수 없어요.
          </p>
          {Object.values(values).some((value) => Boolean(value && contentError(value))) && (
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
            {command.label ?? (verified ? '기록 저장·해결 확인 요청' : '문의 처리 기록 저장')}
          </Button>
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
