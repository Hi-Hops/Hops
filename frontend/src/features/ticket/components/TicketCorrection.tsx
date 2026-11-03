import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { useSystemCommand } from '@/shared/api/useSystemCommand';
import { useFormDraft } from '@/shared/lib/useFormDraft';
import { contentError } from '@/shared/lib/content';
import { Button } from '@/shared/ui/Button';
import { FormField } from '@/shared/ui/FormField';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { RouteModal } from '@/shared/ui/RouteModal';
import type { TicketManagementDetail } from '@/shared/api/types/hopsSystem1';
import {
  assignmentCommand,
  processingCommand,
  getPrimary,
  getSystems,
} from '../api/systemTicketApi';
import { mutableTicket, nullableInput } from '../model/workflow';
import styles from './Workflow.module.css';

type TicketCorrectionProps = {
  ticket: TicketManagementDetail;
  kind: 'assignment' | 'processing';
  onClose: () => void;
};
export function TicketCorrection({ ticket, kind, onClose }: TicketCorrectionProps) {
  const actor = useSession((s) => s.user);
  const assignment = kind === 'assignment';
  const command = useSystemCommand(actor?.id ?? '', kind + ':' + ticket.id);
  const draft = useFormDraft(
    (actor?.id ?? '') + ':' + kind + ':' + ticket.id,
    ticket.ticketRevision,
    {
      systemId: ticket.responsibleSystem.id,
      processingType: ticket.processingType === 'INVESTIGATION' ? 'INCIDENT' : 'INVESTIGATION',
      reason: '',
      evidenceNote: '',
    },
  );
  const systemId = draft.values.systemId ?? '';
  const catalog = useQuery({
    queryKey: ['hops-system-1', 'systems', actor?.id],
    queryFn: ({ signal }) => getSystems(signal),
    enabled: assignment,
  });
  const primary = useQuery({
    queryKey: ['hops-system-1', 'primary', actor?.id, systemId],
    queryFn: ({ signal }) => getPrimary(systemId, signal),
    enabled: assignment && Boolean(systemId),
  });
  const staff =
    actor?.id === ticket.currentPrimary.id &&
    actor.primarySystemIds.includes(ticket.responsibleSystem.id);
  const permitted =
    mutableTicket(ticket) &&
    (assignment
      ? staff || actor?.roles.includes('BIZ_ADMIN')
      : staff && ['INVESTIGATION', 'INCIDENT'].includes(ticket.processingType));
  const reason = draft.values.reason ?? '';
  const evidence = draft.values.evidenceNote ?? '';
  const processingType = draft.values.processingType;
  const invalid =
    Boolean(contentError(reason)) ||
    Boolean(contentError(evidence)) ||
    (assignment
      ? !primary.data || primary.isError || primary.isFetching
      : !['INVESTIGATION', 'INCIDENT'].includes(processingType ?? '') ||
        processingType === ticket.processingType);
  return (
    <RouteModal
      title={assignment ? '책임 시스템 정정' : '처리 유형 정정'}
      description="정정 사유와 직접 확인한 근거를 남겨 주세요. 저장 후 서버가 최신 배정과 버전을 확인해요."
      onClose={onClose}
      footer={null}
      size="compact"
      returnFocusId={'correct-' + kind + '-' + ticket.id}
    >
      <form
        className={styles.form}
        onSubmit={(event) => {
          event.preventDefault();
          if (!permitted || (!command.pending && (invalid || draft.stale))) return;
          if (!assignment && processingType !== 'INVESTIGATION' && processingType !== 'INCIDENT')
            return;
          void command.execute(
            (requestId) =>
              assignment
                ? assignmentCommand(ticket.id, {
                    requestId,
                    expectedTicketRevision: draft.version,
                    responsibleSystemId: systemId,
                    reason,
                    evidenceMessageIds: [],
                    evidenceNote: nullableInput(evidence),
                  })
                : processingCommand(ticket.id, {
                    requestId,
                    expectedTicketRevision: draft.version,
                    processingType: processingType === 'INCIDENT' ? 'INCIDENT' : 'INVESTIGATION',
                    reason,
                    evidenceMessageIds: [],
                    evidenceNote: nullableInput(evidence),
                  }),
            () => {
              draft.clear();
              onClose();
            },
          );
        }}
      >
        {!permitted && <p role="alert">현재 상태 또는 권한으로 정정할 수 없어요.</p>}
        <fieldset disabled={command.locked || !permitted}>
          {assignment ? (
            <FormField id="correction-system" label="책임 시스템">
              <select
                id="correction-system"
                value={systemId}
                onChange={(e) => {
                  draft.change('systemId', e.target.value);
                }}
              >
                {catalog.data?.map((system) => (
                  <option key={system.id} value={system.id}>
                    {system.name}
                  </option>
                ))}
              </select>
            </FormField>
          ) : (
            <FormField id="correction-type" label="처리 유형">
              <select
                id="correction-type"
                value={processingType ?? ''}
                onChange={(e) => {
                  draft.change('processingType', e.target.value);
                }}
              >
                <option value="INVESTIGATION">조사</option>
                <option value="INCIDENT">장애</option>
              </select>
            </FormField>
          )}
          {assignment &&
            (catalog.isPending || primary.isPending ? (
              <LoadingState label="현재 주담당자를 확인하고 있어요" />
            ) : catalog.isError || primary.isError ? (
              <ErrorState
                title="시스템과 주담당자를 확인하지 못했어요"
                onRetry={() => {
                  void catalog.refetch();
                  void primary.refetch();
                }}
              />
            ) : (
              <p>
                변경 후 주담당자: <strong>{primary.data.primaryUser.name}</strong>
              </p>
            ))}
          <FormField id="correction-reason" label="정정 사유">
            <textarea
              id="correction-reason"
              value={reason}
              onChange={(e) => {
                draft.change('reason', e.target.value);
              }}
            />
          </FormField>
          <FormField
            id="correction-evidence"
            label="확인한 근거"
            hint="직접 관측한 내용이나 접근 권한이 있는 업무 근거를 입력하세요."
          >
            <textarea
              id="correction-evidence"
              value={evidence}
              onChange={(e) => {
                draft.change('evidenceNote', e.target.value);
              }}
            />
          </FormField>
        </fieldset>
        {draft.stale && (
          <div className={styles.warning} role="alert">
            티켓이 변경됐어요. 작성 내용과 최신 티켓을 비교해 주세요.
            <Button variant="secondary" disabled={command.locked} onClick={draft.reviewLatest}>
              최신 티켓 확인 후 이어쓰기
            </Button>
          </div>
        )}
        <div className={styles.actions}>
          <Button variant="secondary" onClick={onClose}>
            취소
          </Button>
          <Button
            type="submit"
            disabled={
              !permitted ||
              command.busy ||
              command.expired ||
              command.remaining > 0 ||
              (!command.pending && (invalid || draft.stale))
            }
          >
            {command.label ?? '정정 저장'}
          </Button>
        </div>
        {command.error && <p role="alert">{command.error}</p>}
        {command.remaining > 0 && (
          <p role="status">{command.remaining}초 후 다시 요청할 수 있어요.</p>
        )}
      </form>
    </RouteModal>
  );
}
