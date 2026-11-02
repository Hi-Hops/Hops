import { useEffect, useMemo, useRef } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { useSubscription } from '@/features/realtime';
import { systemStatus } from '@/features/inquiry';
import { ApiError } from '@/shared/api/client';
import { displayDate } from '@/shared/lib/date';
import { Button } from '@/shared/ui/Button';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { RouteModal } from '@/shared/ui/RouteModal';
import {
  getManagementTicket,
  getRelatedMessages,
  getSystemTicket,
  systemTicketKeys,
} from '../api/systemTicketApi';
import type {
  RelatedMessage,
  TicketDetail,
  TicketManagementDetail,
} from '@/shared/api/types/hopsSystem1';
import { processingLabels } from '../model/workflow';
import styles from './Tickets.module.css';

function hasRequest(value: TicketManagementDetail | TicketDetail): value is TicketDetail {
  return 'standardRequest' in value && 'workRecords' in value;
}
type RequestContentProps = { ticket: TicketDetail };
function RequestContent({ ticket }: RequestContentProps) {
  const actor = useSession((s) => s.user?.id);
  const request = ticket.standardRequest;
  const detailFields = [
    ['taskOrScreen', '업무·화면'],
    ['requestedAccess', '요청 권한'],
    ['businessPurpose', '업무 목적'],
    ['expectedBehavior', '기대한 동작'],
    ['actualBehavior', '실제 현상'],
    ['occurredAt', '발생 시점'],
    ['currentWorkflow', '현재 업무 흐름'],
    ['desiredChange', '원하는 변경'],
  ] as const;
  const query = useInfiniteQuery({
    queryKey: ['hops-system-1', 'related', actor, ticket.id],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => getRelatedMessages(ticket.id, pageParam, signal),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const messages = useMemo(() => {
    const unique = new Map<string, RelatedMessage>();
    for (const message of query.data?.pages.flatMap((page) => page.items) ?? []) {
      if ((unique.get(message.id)?.revision ?? 0) < message.revision)
        unique.set(message.id, message);
    }
    return [...unique.values()];
  }, [query.data]);
  return (
    <div className={styles.requestSections}>
      <section className={styles.requestCard}>
        <h3>표준 요청</h3>
        <dl className={styles.facts}>
          <dt>문의 요약</dt>
          <dd>{request.summary}</dd>
          <dt>처리 유형</dt>
          <dd>{processingLabels[ticket.processingType] ?? '유형 확인 필요'}</dd>
          <dt>보고 시스템</dt>
          <dd>{ticket.reportedSystem.name}</dd>
        </dl>
        {detailFields.some(([key]) => request.details[key] !== null) && (
          <dl className={styles.facts}>
            {detailFields.map(([key, label]) => {
              const value = request.details[key];
              return value === null ? null : (
                <div key={key}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              );
            })}
          </dl>
        )}
        {!!request.details.acceptanceCriteria.length && (
          <>
            <h4>인수 조건</h4>
            {request.details.acceptanceCriteria.map((item, index) => (
              <p key={index}>
                {item.text} ·{' '}
                {item.confirmation === 'CONFIRMED' && item.confirmedBy
                  ? '담당자 확인'
                  : item.confirmation === 'PROPOSED'
                    ? '제안·사람 확인 필요'
                    : '확인 상태 미확인'}
              </p>
            ))}
          </>
        )}
        <h4>확인된 사실</h4>
        {request.confirmedFacts.map((fact, i) => (
          <p key={String(i) + fact.text}>• {fact.text}</p>
        ))}
        {!request.confirmedFacts.length && <p>저장된 확인 사실이 없어요.</p>}
        <h4>미확인 항목</h4>
        {request.unknowns.length ? (
          request.unknowns.map((text) => <p key={text}>{text}</p>)
        ) : (
          <p>저장된 미확인 항목이 없어요.</p>
        )}
        <h4>추정</h4>
        {request.assumptions.length ? (
          request.assumptions.map((fact, i) => <p key={String(i) + fact.text}>{fact.text}</p>)
        ) : (
          <p>저장된 추정이 없어요.</p>
        )}
      </section>
      <section className={styles.requestCard}>
        <h3>관련 지식·검토 근거</h3>
        {request.knowledge.length ? (
          request.knowledge.map((item) => (
            <article className={styles.evidence} key={item.id}>
              <strong>
                {item.title} · 문서 v{item.documentVersion}
              </strong>
              <p>{item.excerpt}</p>
              <p>{item.applicability}</p>
              <small>
                {item.systemId === null
                  ? '공통 정책'
                  : ticket.responsibleSystem.id === item.systemId
                    ? ticket.responsibleSystem.name
                    : '관련 시스템'}{' '}
                · 적용 버전 {item.appliesToSystemVersion ?? '미확인'} ·{' '}
                {item.origin === 'SYNTHETIC'
                  ? '가상 자료'
                  : item.origin === 'DEMO_VERIFIED'
                    ? '데모 검증 자료'
                    : item.origin === 'OPERATIONAL'
                      ? '운영 자료'
                      : '출처 확인 필요'}
              </small>
            </article>
          ))
        ) : (
          <p>저장된 관련 지식이 없어요. 근거를 추가로 확인해 주세요.</p>
        )}
      </section>
      <section className={styles.requestCard}>
        <h3>관련 대화·보완 정보</h3>
        <p className={styles.cardMeta}>해당 문제에서 조회가 허용된 원문 구간만 표시해요.</p>
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <ErrorState
            title="관련 대화를 불러오지 못했어요"
            onRetry={() => {
              void query.refetch();
            }}
          />
        ) : (
          <>
            {messages.map((message) => (
              <article className={styles.evidence} key={message.id}>
                <strong>
                  {message.sender === 'USER' ? '문의자' : 'Hops'} · {displayDate(message.createdAt)}
                </strong>
                <p>{message.content}</p>
                {!!message.revisions.length && (
                  <details>
                    <summary>정정 이력</summary>
                    {message.revisions.map((revision) => (
                      <p key={revision.revisionNo}>{revision.content}</p>
                    ))}
                  </details>
                )}
              </article>
            ))}
            {!messages.length && (
              <p>공개 가능한 관련 대화가 없어요. 위 표준 요청을 확인해 주세요.</p>
            )}
            {query.hasNextPage && (
              <Button
                variant="secondary"
                disabled={query.isFetchingNextPage}
                onClick={() => {
                  void query.fetchNextPage();
                }}
              >
                관련 대화 더 보기
              </Button>
            )}
          </>
        )}
      </section>
    </div>
  );
}
type SystemTicketDetailProps = { id: string; onClose?: () => void };
export function SystemTicketDetail({ id, onClose }: SystemTicketDetailProps) {
  const user = useSession((s) => s.user);
  const location = useLocation();
  const [search] = useSearchParams();
  const admin = location.pathname.startsWith('/admin/');
  const base = admin ? '/admin/tickets' : '/tickets';
  const allowed = Boolean(
    user && (admin ? user.roles.includes('BIZ_ADMIN') : user.primarySystemIds.length),
  );
  const subscription = useSubscription(allowed && !admin ? { type: 'TICKET', id } : null);
  const query = useQuery({
    queryKey: systemTicketKeys.detail(user?.id ?? '', id, admin),
    queryFn: ({ signal }) =>
      admin ? getManagementTicket(id, signal) : getSystemTicket(id, signal),
    enabled: allowed,
    refetchInterval: 5000,
  });
  const titleRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (query.isSuccess && !onClose) titleRef.current?.focus();
  }, [query.isSuccess, id, onClose]);
  const data = query.data;
  const back = new URLSearchParams(search);
  back.delete('tab');
  const visible =
    allowed &&
    !subscription.denied &&
    (admin || !['connecting', 'disconnected'].includes(subscription.state));
  const heading =
    data && query.isSuccess && visible ? data.ticketNumber + ' · ' + data.title : '티켓 상세';
  const content =
    !allowed || subscription.denied ? (
      <ErrorState title="이 시스템 티켓을 볼 권한이 없어요" />
    ) : !admin && ['connecting', 'disconnected'].includes(subscription.state) ? (
      <LoadingState label="현재 권한과 저장 상태를 다시 확인하고 있어요" />
    ) : query.isPending ? (
      <LoadingState />
    ) : query.isError ? (
      <ErrorState
        title={
          query.error instanceof ApiError && query.error.status === 403
            ? '이 시스템 티켓을 볼 권한이 없어요'
            : query.error instanceof ApiError && query.error.status === 404
              ? '티켓을 찾을 수 없어요'
              : '티켓을 불러오지 못했어요'
        }
        onRetry={() => {
          void query.refetch();
        }}
      />
    ) : (
      data && (
        <div className={styles.detailLayout}>
          <div className={styles.detailMain}>
            {!admin && hasRequest(data) ? (
              <RequestContent ticket={data} />
            ) : (
              <section className={styles.requestCard}>
                <h3>업무 메타데이터</h3>
                <p>관리 권한에서 허용된 배정·상태 정보예요.</p>
              </section>
            )}
          </div>
          <aside className={styles.properties}>
            <StatusBadge {...systemStatus(data.state)} />
            <dl>
              <dt>보고 시스템</dt>
              <dd>{data.reportedSystem.name}</dd>
              <dt>책임 시스템</dt>
              <dd>{data.responsibleSystem.name}</dd>
              <dt>배정된 담당자</dt>
              <dd>{data.assignee.name}</dd>
              <dt>현재 주담당자</dt>
              <dd>{data.currentPrimary.name}</dd>
              <dt>처리 유형</dt>
              <dd>{processingLabels[data.processingType] ?? '유형 확인 필요'}</dd>
              <dt>생성 시각</dt>
              <dd>{displayDate(data.createdAt)}</dd>
            </dl>
            <section className={styles.assignmentHistory}>
              <h3>배정 이력</h3>
              {data.assignmentHistory.map((entry) => (
                <article key={entry.id}>
                  <strong>{entry.assignee.name}</strong>
                  <p>{entry.reason}</p>
                  <small>{displayDate(entry.createdAt)}</small>
                </article>
              ))}
            </section>
          </aside>
        </div>
      )
    );
  return onClose ? (
    <RouteModal
      title={heading}
      description={admin ? '업무 관리자 · 제한된 상세' : '표준 요청·근거·배정 정보'}
      onClose={onClose}
      returnFocusId={'ticket-' + id}
      padded={false}
      footer={null}
    >
      {content}
    </RouteModal>
  ) : (
    <main className={styles.page}>
      <header className={styles.pageHead}>
        <div>
          <h1 ref={titleRef} tabIndex={-1}>
            {heading}
          </h1>
          <p>저장된 업무 정보를 확인하세요.</p>
        </div>
        <Link to={{ pathname: base, search: back.toString() }}>목록으로</Link>
      </header>
      {content}
    </main>
  );
}
