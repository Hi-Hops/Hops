import { useState } from 'react';
import type { ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import type { InquiryResult, Problem } from '@/shared/api/types/hopsSystem1';
import { Button } from '@/shared/ui/Button';
import { RouteModal } from '@/shared/ui/RouteModal';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { ResolutionControls } from './ResolutionControls';
import { SystemCommandButton } from './SystemCommandButton';
import {
  confirmSystemCommand,
  getCreationResult,
  getInquirySystems,
  linkProblemsCommand,
  retryCreationCommand,
} from '../api/systemInquiryApi';
import { QUESTION_LIMIT, isCreationFailed, resultCounts } from '../model/resultSummary';
import { editableInquiryStatus, systemStatus, ticketStates } from '../model/systemStatus';
import styles from './ResultPanel.module.css';

const needsSystem = (p: Problem) =>
  !p.reportedSystemId && ['WAITING_SYSTEM', 'ANALYZING'].includes(p.state);

function SystemSelect({ inquiry, problem }: { inquiry: InquiryResult; problem: Problem }) {
  const actor = useSession((s) => s.user?.id);
  const [selected, setSelected] = useState('');
  const catalog = useQuery({
    queryKey: ['hops-system-1', 'systems', actor],
    queryFn: ({ signal }) => getInquirySystems(signal),
  });
  return (
    <div className={styles.systemSelect}>
      <label htmlFor={`confirm-system-${problem.id}`}>{problem.summary}</label>
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
      {catalog.isError && <p role="alert">시스템 목록을 불러오지 못했어요.</p>}
    </div>
  );
}
function CreationAction({ inquiry, problem }: { inquiry: InquiryResult; problem: Problem }) {
  const client = useQueryClient();
  const [notice, setNotice] = useState('');
  const creation = problem.creationResult;
  if (!creation) return null;
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
      {creation.outcome === 'NOT_CREATED' ? (
        <SystemCommandButton
          operationKey={`creation:${creation.workItemId}`}
          createCommand={(requestId) =>
            retryCreationCommand(creation.workItemId, {
              requestId,
              expectedInquiryVersion: inquiry.inquiryVersion,
            })
          }
          label="↻ 이 항목만 다시 시도"
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
    </>
  );
}
type RowView = {
  tone: 'plain' | 'danger' | 'attention';
  lead: string;
  leadTone?: 'attention' | 'muted';
  title: string;
  aside?: ReactNode;
  note?: string;
};
function rowView(inquiry: InquiryResult, problem: Problem): RowView {
  const questions = `질문 ${problem.questionCount}/${QUESTION_LIMIT}`;
  if (problem.ticket)
    return {
      tone: 'plain',
      lead: problem.ticket.ticketNumber,
      title: problem.ticket.title,
      aside: <span className={styles.chip}>{problem.ticket.responsibleSystem.name}</span>,
      note: `담당자 ${problem.ticket.assignee.name} · ${
        problem.ticket.state in ticketStates
          ? ticketStates[problem.ticket.state as keyof typeof ticketStates]
          : systemStatus(problem.ticket.state).label
      }`,
    };
  if (isCreationFailed(problem)) {
    const outcome = problem.creationResult?.outcome;
    return {
      tone: outcome === 'NOT_CREATED' ? 'danger' : 'attention',
      lead: '—',
      leadTone: 'muted',
      title: problem.summary,
      aside: <CreationAction inquiry={inquiry} problem={problem} />,
      note:
        outcome === 'NOT_CREATED'
          ? '저장하지 못했어요. 다시 시도해도 다른 티켓이 중복으로 만들어지지 않아요.'
          : outcome === 'UNKNOWN'
            ? '저장 결과를 확인하지 못했어요. 결과를 확인한 뒤 필요하면 다시 시도해요.'
            : '접수 결과 확인 중이에요.',
    };
  }
  if (problem.state === 'WAITING_USER')
    return {
      tone: 'plain',
      lead: '추가 확인',
      leadTone: 'attention',
      title: problem.summary,
      aside: <span className={styles.count}>{questions}</span>,
      note: `질문에 답해 주시면 담당 시스템과 처리 방법을 판단해요. 질문은 최대 ${String(QUESTION_LIMIT)}번이에요.`,
    };
  if (problem.state === 'WAITING_SYSTEM')
    return {
      tone: 'plain',
      lead: '시스템 확인',
      leadTone: 'attention',
      title: problem.summary,
      aside: <span className={styles.count}>{questions}</span>,
      note: '질문으로 시스템을 확인하지 못했어요. 입력창 위에서 지원 시스템을 선택해 주세요. 선택하지 않아도 확인 대기는 유지돼요.',
    };
  if (problem.state === 'OUT_OF_SCOPE')
    return {
      tone: 'plain',
      lead: '범위 밖',
      leadTone: 'muted',
      title: problem.summary,
      note: '지원 범위 밖으로 티켓을 만들지 않았어요.',
    };
  return {
    tone: 'plain',
    lead: problem.guide ? '안내' : '확인 중',
    leadTone: 'muted',
    title: problem.summary,
    aside: <StatusBadge {...systemStatus(problem.state)} />,
  };
}
function ProblemRow({ inquiry, problem }: { inquiry: InquiryResult; problem: Problem }) {
  const view = rowView(inquiry, problem);
  return (
    <li className={`${styles.row} ${styles[view.tone]}`} aria-label={problem.summary}>
      <div className={styles.rowHead}>
        <span className={`${styles.lead} ${view.leadTone ? styles[view.leadTone] : ''}`}>
          {view.lead}
        </span>
        <strong className={styles.rowTitle}>{view.title}</strong>
        {view.aside && <div className={styles.aside}>{view.aside}</div>}
      </div>
      {view.note && <p className={styles.note}>{view.note}</p>}
      {problem.guide?.evidence.map((e) => (
        <div className={styles.evidence} key={e.id}>
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
      {problem.activeResolutionRequest && (
        <ResolutionControls request={problem.activeResolutionRequest} />
      )}
    </li>
  );
}
function bannerFor(result: InquiryResult) {
  const c = resultCounts(result);
  const failure = [
    c.notCreated ? `${String(c.notCreated)}건은 저장하지 못했어요` : '',
    c.checking ? `${String(c.checking)}건은 접수 결과를 확인 중이에요` : '',
  ]
    .filter(Boolean)
    .join(', ');
  if (c.tickets && failure)
    return { text: `! 티켓 ${String(c.tickets)}건을 만들었고, ${failure}`, tone: 'attention' };
  if (failure) return { text: `! 티켓 ${failure}`, tone: c.notCreated ? 'danger' : 'attention' };
  if (c.tickets && c.resolved === c.tickets)
    return { text: '티켓이 모두 해결됐어요', tone: 'success' };
  if (c.tickets) return { text: '티켓을 만들고 담당 시스템에 배정했어요', tone: 'success' };
  return { text: `문의 처리 현황 · 문제 ${String(result.problems.length)}개`, tone: 'neutral' };
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
// 보고 시스템 선택은 결과 카드가 아니라 입력창 위에 모아서 보여준다.
export function SystemConfirmations({ inquiry }: { inquiry: InquiryResult }) {
  const targets = inquiry.problems.filter(needsSystem);
  if (!targets.length) return null;
  return (
    <section className={styles.systemDock} aria-label="보고 시스템 선택">
      <strong>어느 시스템에서 생긴 문제인지 선택해 주세요</strong>
      <p className={styles.meta}>선택하지 않아도 확인 대기 상태는 유지돼요.</p>
      {targets.map((problem) => (
        <SystemSelect key={problem.id} inquiry={inquiry} problem={problem} />
      ))}
    </section>
  );
}
// 결과 패널은 답변 위치에 따라 다시 마운트되므로, 모달을 닫은 revision은 상위 화면이 보관한다.
export function SystemResultPanel({
  result,
  dismissedRevision,
  onDismiss,
}: {
  result: InquiryResult;
  dismissedRevision: number;
  onDismiss: (revision: number) => void;
}) {
  const hasResult = result.problems.some((p) => p.creationResult);
  const banner = bannerFor(result);
  return (
    <section className={styles.panel} id="problem-confirmations" aria-label="문제별 처리 결과">
      <h2 className={`${styles.banner} ${styles[banner.tone]}`}>{banner.text}</h2>
      <ul className={styles.rows}>
        {result.problems.map((problem) => (
          <ProblemRow key={problem.id} inquiry={result} problem={problem} />
        ))}
      </ul>
      {result.pendingTargetMessageIds.map((id) => (
        <PendingTarget key={id} inquiry={result} messageId={id} />
      ))}
      {hasResult && result.resultRevision > dismissedRevision && (
        <RouteModal
          title="티켓 접수 결과"
          description="서버에 저장된 문제별 접수·배정 결과예요. 해결 완료와는 구분해요."
          onClose={() => {
            onDismiss(result.resultRevision);
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
