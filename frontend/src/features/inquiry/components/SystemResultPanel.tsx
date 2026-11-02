import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import type { InquiryResult, Problem } from '@/shared/api/types/hopsSystem1';
import { Button } from '@/shared/ui/Button';
import { RouteModal } from '@/shared/ui/RouteModal';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { SystemCommandButton } from './SystemCommandButton';
import {
  confirmSystemCommand,
  getCreationResult,
  getInquirySystems,
  linkProblemsCommand,
  retryCreationCommand,
} from '../api/systemInquiryApi';
import { editableInquiryStatus, systemStatus } from '../model/systemStatus';
import styles from './ResultPanel.module.css';

function ProblemControls({ inquiry, problem }: { inquiry: InquiryResult; problem: Problem }) {
  const actor = useSession((s) => s.user?.id);
  const client = useQueryClient();
  const [selected, setSelected] = useState('');
  const [notice, setNotice] = useState('');
  const needsSystem =
    !problem.reportedSystemId && ['WAITING_SYSTEM', 'ANALYZING'].includes(problem.state);
  const catalog = useQuery({
    queryKey: ['hops-system-1', 'systems', actor],
    queryFn: ({ signal }) => getInquirySystems(signal),
    enabled: needsSystem,
  });
  const creation = problem.creationResult;
  async function check() {
    try {
      await getCreationResult(creation?.workItemId ?? problem.id);
      await client.invalidateQueries({ queryKey: ['hops-system-1'] });
      setNotice('현재 저장 결과를 확인했어요.');
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '결과를 확인하지 못했어요.');
    }
  }
  return (
    <>
      {needsSystem && (
        <div>
          <label htmlFor={`confirm-system-${problem.id}`}>보고 시스템</label>
          <select
            id={`confirm-system-${problem.id}`}
            value={selected}
            onChange={(e) => {
              setSelected(e.target.value);
            }}
          >
            <option value="">지원 시스템 선택</option>
            {catalog.data?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          {catalog.isError && <p role="alert">시스템 목록을 불러오지 못했어요.</p>}
          <SystemCommandButton
            operationKey={`system:${inquiry.id}:${problem.id}`}
            createCommand={(requestId) =>
              confirmSystemCommand(inquiry.id, problem.id, {
                requestId,
                expectedInquiryVersion: inquiry.inquiryVersion,
                systemId: selected,
              })
            }
            label="시스템 확인"
            disabled={!selected || !editableInquiryStatus(inquiry.status)}
          />
        </div>
      )}
      {creation && creation.outcome !== 'STORED' && (
        <div className={styles.failure}>
          <strong>
            {creation.outcome === 'NOT_CREATED'
              ? '티켓 미생성이 확인됐어요'
              : creation.outcome === 'UNKNOWN'
                ? '저장 결과를 확인하지 못했어요'
                : '접수 결과 확인 중이에요'}
          </strong>
          <p>다른 문제의 저장된 티켓은 유지돼요.</p>
          {creation.outcome === 'NOT_CREATED' ? (
            <SystemCommandButton
              operationKey={`creation:${creation.workItemId}`}
              createCommand={(requestId) =>
                retryCreationCommand(creation.workItemId, {
                  requestId,
                  expectedInquiryVersion: inquiry.inquiryVersion,
                })
              }
              label="실패 항목 재시도"
              disabled={!editableInquiryStatus(inquiry.status)}
            />
          ) : (
            <Button
              variant="secondary"
              onClick={() => {
                void check();
              }}
            >
              저장 결과 확인
            </Button>
          )}
          {notice && <p role="status">{notice}</p>}
        </div>
      )}
    </>
  );
}
function PendingTarget({ inquiry, messageId }: { inquiry: InquiryResult; messageId: string }) {
  const [selected, setSelected] = useState<string[]>([]);
  return (
    <section className={styles.problem}>
      <label htmlFor={`target-${messageId}`}>보완 정보를 연결할 기존 문제</label>
      <select
        multiple
        id={`target-${messageId}`}
        value={selected}
        onChange={(e) => {
          setSelected([...e.target.selectedOptions].map((o) => o.value));
        }}
      >
        {inquiry.problems
          .filter((p) => p.state !== 'RESOLVED' && p.state !== 'OUT_OF_SCOPE')
          .map((p) => (
            <option key={p.id} value={p.id}>
              {p.summary}
            </option>
          ))}
      </select>
      <SystemCommandButton
        operationKey={`problem-links:${inquiry.id}:${messageId}`}
        createCommand={(requestId) =>
          linkProblemsCommand(inquiry.id, messageId, {
            requestId,
            expectedInquiryVersion: inquiry.inquiryVersion,
            problemIds: selected,
          })
        }
        label="보완 대상 연결"
        disabled={!selected.length || !editableInquiryStatus(inquiry.status)}
      />
    </section>
  );
}
export function SystemResultPanel({ result }: { result: InquiryResult }) {
  const [dismissed, setDismissed] = useState(0);
  const hasResult = result.problems.some((p) => p.creationResult);
  return (
    <section className={styles.panel} id="problem-confirmations" aria-label="문제별 처리 결과">
      <header className={styles.header}>
        <h2>문의 처리 현황</h2>
        <span>
          {result.problems.length}개 문제 · 티켓 {result.problems.filter((p) => p.ticket).length}개
        </span>
      </header>
      <div className={styles.problems}>
        {result.problems.map((problem) => (
          <section className={styles.problem} key={problem.id} aria-label={problem.summary}>
            <div className={styles.heading}>
              <h3>{problem.summary}</h3>
              <StatusBadge {...systemStatus(problem.state)} />
            </div>
            <p className={styles.meta}>추가 질문 {problem.questionCount} / 5회</p>
            {problem.state === 'OUT_OF_SCOPE' && <p>지원 범위 밖으로 티켓을 만들지 않았어요.</p>}
            {problem.ticket && (
              <article
                className={styles.ticket}
                aria-label={`저장된 티켓 ${problem.ticket.ticketNumber}`}
              >
                <div className={styles.heading}>
                  <span className={styles.number}>{problem.ticket.ticketNumber}</span>
                  <strong>{problem.ticket.title}</strong>
                </div>
                <p>보고 시스템: {problem.ticket.reportedSystem.name}</p>
                <p>
                  책임 시스템: {problem.ticket.responsibleSystem.name} · 담당자:{' '}
                  {problem.ticket.assignee.name}
                </p>
                <StatusBadge {...systemStatus(problem.ticket.state)} />
              </article>
            )}
            {problem.guide?.evidence.map((e) => (
              <div key={e.id}>
                <strong>
                  {e.title} · 문서 v{e.documentVersion}
                </strong>
                <p>{e.excerpt}</p>
                <small>
                  {e.systemId === null ? '공통 정책' : '직원용 안내'} · 적용 버전{' '}
                  {e.appliesToSystemVersion ?? '미확인'} · {e.applicability}
                </small>
              </div>
            ))}
            <ProblemControls inquiry={result} problem={problem} />
          </section>
        ))}
      </div>
      {result.pendingTargetMessageIds.map((id) => (
        <PendingTarget key={id} inquiry={result} messageId={id} />
      ))}
      {hasResult && result.resultRevision > dismissed && (
        <RouteModal
          title="티켓 접수 결과"
          description="서버에 저장된 문제별 접수·배정 결과예요. 해결 완료와는 구분해요."
          onClose={() => {
            setDismissed(result.resultRevision);
          }}
          returnFocusId="chat-title"
        >
          <ul>
            {result.problems.map((p) => (
              <li key={p.id}>
                {p.summary} ·{' '}
                {p.ticket
                  ? `${p.ticket.ticketNumber} / ${p.ticket.responsibleSystem.name} / ${p.ticket.assignee.name}`
                  : p.creationResult?.outcome === 'NOT_CREATED'
                    ? '미생성 확인'
                    : p.creationResult
                      ? '저장 결과 확인 중'
                      : systemStatus(p.state).label}
              </li>
            ))}
          </ul>
        </RouteModal>
      )}
    </section>
  );
}
