import { displayTicketNumber } from '@/shared/lib/ticketNumber';
import { useMemo, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { ReconnectNotice, reconnect, useSubscription } from '@/features/realtime';
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
  getRecommendedCases,
  getRelatedMessages,
  getSystemTicket,
  systemTicketKeys,
} from '../api/systemTicketApi';
import type {
  KnowledgeEvidence,
  RelatedMessage,
  TicketDetail,
  TicketManagementDetail,
} from '@/shared/api/types/hopsSystem1';
import { processingLabels, mutableTicket, workKinds } from '../model/workflow';
import { WorkRecordEditor } from './WorkRecordEditor';
import { TicketCorrection } from './TicketCorrection';
import { CaseGenerationPanel } from './CaseGenerationPanel';
import styles from './Tickets.module.css';
import workflow from './Workflow.module.css';

function hasRequest(value: TicketManagementDetail | TicketDetail): value is TicketDetail {
  return 'standardRequest' in value && 'workRecords' in value;
}
type RequestContentProps = { ticket: TicketDetail };
type KnowledgeItemProps = RequestContentProps & { item: KnowledgeEvidence };
function KnowledgeItem({ item, ticket }: KnowledgeItemProps) {
  return (
    <article className={styles.evidence}>
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
  );
}
/** 기록 중 탭을 오가지 않도록 요청 내용을 같은 탭에서 펼쳐 본다. 펼칠 때만 관련 대화를 조회한다. */
function RequestReference({ ticket }: RequestContentProps) {
  const [open, setOpen] = useState(false);
  return (
    <details
      className={workflow.reference}
      open={open}
      onToggle={(event) => {
        setOpen(event.currentTarget.open);
      }}
    >
      <summary>요청 내역 참고</summary>
      {open && <RequestContent ticket={ticket} />}
    </details>
  );
}
function RequestContent({ ticket }: RequestContentProps) {
  const actor = useSession((s) => s.user?.id);
  const recommendations = useQuery({
    queryKey: ['hops-system-1', 'recommended-cases', actor, ticket.id],
    queryFn: ({ signal }) => getRecommendedCases(ticket.id, signal),
    enabled: Boolean(actor),
    retry: false,
    refetchOnWindowFocus: false,
    staleTime: 0,
  });
  const request = ticket.standardRequest;
  const cases = request.knowledge.filter((item) => item.kind === 'CASE');
  const documents = request.knowledge.filter((item) => item.kind !== 'CASE');
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
      <details className={styles.requestFold}>
        <summary>
          <h3>표준 요청</h3>
        </summary>
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
      </details>
      <section className={styles.requestCard}>
        <h3>확인된 사실</h3>
        {request.confirmedFacts.map((fact, i) => (
          <p key={String(i) + fact.text}>• {fact.text}</p>
        ))}
        {!request.confirmedFacts.length && <p>저장된 확인 사실이 없어요.</p>}
      </section>
      <section className={styles.requestCard}>
        <h3>채팅 내역</h3>
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <ErrorState
            title="채팅 내역을 불러오지 못했어요"
            onRetry={() => {
              void query.refetch();
            }}
          />
        ) : (
          <>
            <div className={styles.chatLog}>
              {messages.map((message) => (
                <article
                  className={styles.chatMessage}
                  data-sender={message.sender}
                  key={message.id}
                >
                  <div className={styles.chatMeta}>
                    <strong>{message.sender === 'USER' ? '문의자' : 'Hops'}</strong>
                    <time dateTime={message.createdAt}>{displayDate(message.createdAt)}</time>
                  </div>
                  <p className={styles.chatText}>{message.content}</p>
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
            </div>
            {!messages.length && (
              <p>공개 가능한 채팅 내역이 없어요. 위 표준 요청을 확인해 주세요.</p>
            )}
            {query.hasNextPage && (
              <Button
                variant="secondary"
                disabled={query.isFetchingNextPage}
                onClick={() => {
                  void query.fetchNextPage();
                }}
              >
                채팅 내역 더 보기
              </Button>
            )}
          </>
        )}
      </section>
      <section className={styles.requestCard}>
        <h3>추천 해결 사례</h3>
        <p>문의 제목을 기준으로 찾은 후보예요. 처리 전에 증상과 적용 조건을 확인해 주세요.</p>
        {recommendations.isError ? (
          <ErrorState
            title="추천 사례를 불러오지 못했어요"
            onRetry={() => {
              void recommendations.refetch();
            }}
          />
        ) : recommendations.isFetching || recommendations.isPending ? (
          <LoadingState label="추천 사례를 찾고 있어요" />
        ) : recommendations.data.length ? (
          recommendations.data.map((item) => (
            <KnowledgeItem key={item.id} item={item} ticket={ticket} />
          ))
        ) : (
          <p>추천할 사례가 없어요.</p>
        )}
      </section>
      <section className={styles.requestCard}>
        <h3>문의에 저장된 과거 해결 사례</h3>
        {cases.length ? (
          <>
            {cases.map((item) => (
              <KnowledgeItem key={item.id} item={item} ticket={ticket} />
            ))}
          </>
        ) : (
          <p>관련 과거 해결 사례가 없어요.</p>
        )}
        {!!documents.length && (
          <details className={styles.documents}>
            <summary>참고 문서 {documents.length}건</summary>
            {documents.map((item) => (
              <KnowledgeItem key={item.id} item={item} ticket={ticket} />
            ))}
          </details>
        )}
      </section>
    </div>
  );
}
type SystemTicketDetailProps = { id: string; onClose: () => void; returnFocusId?: string };
export function SystemTicketDetail({ id, onClose, returnFocusId }: SystemTicketDetailProps) {
  const user = useSession((s) => s.user);
  const token = useSession((s) => s.token);
  const location = useLocation();
  const navigationState: unknown = location.state;
  const [search] = useSearchParams();
  const admin = location.pathname.startsWith('/admin/');
  const tab = admin ? 'request' : (search.get('tab') ?? 'request');
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
  const [correction, setCorrection] = useState<'assignment' | 'processing' | null>(null);
  const data = query.data;
  const back = new URLSearchParams(search);
  back.delete('tab');
  const linkFor = (value: string) => {
    const next = new URLSearchParams(back);
    next.set('tab', value);
    return { pathname: base + '/' + id, search: next.toString() };
  };
  const staff =
    data &&
    user?.id === data.currentPrimary.id &&
    user.primarySystemIds.includes(data.responsibleSystem.id);
  const canCorrect = data && mutableTicket(data) && (Boolean(staff) || admin);
  const visible =
    allowed &&
    !subscription.denied &&
    (admin || !['connecting', 'disconnected'].includes(subscription.state));
  const shownTicket = data && query.isSuccess && visible ? data : null;
  const heading = shownTicket
    ? displayTicketNumber(shownTicket.ticketNumber) + ' · ' + shownTicket.title
    : '티켓 상세';
  const content =
    !allowed || subscription.denied ? (
      <ErrorState title="이 시스템 티켓을 볼 권한이 없어요" />
    ) : !admin && subscription.state === 'disconnected' ? (
      // 모달이 바깥을 가려 전역 재연결 안내를 누를 수 없으므로 상세 안에서 다시 연결한다.
      <ErrorState
        title="실시간 연결이 끊겼어요"
        description="보호 내용을 가렸어요. 다시 연결하면 현재 권한과 저장 상태를 확인해요."
        onRetry={() => {
          reconnect(token);
        }}
      />
    ) : !admin && subscription.state === 'connecting' ? (
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
        <>
          {!admin && (
            <nav className={styles.tabs} aria-label="티켓 상세 탭">
              {[
                ['request', '요청 내역'],
                ['work', '문의 처리 기록'],
                ['case', '해결 사례'],
              ].map(([value, label]) => (
                <Link
                  key={value}
                  aria-current={tab === value ? 'page' : undefined}
                  to={linkFor(value ?? 'request')}
                  replace
                  state={navigationState}
                >
                  {label}
                </Link>
              ))}
            </nav>
          )}
          <div className={styles.detailLayout}>
            <div className={styles.detailMain}>
              {tab === 'request' ? (
                !admin && hasRequest(data) ? (
                  <RequestContent ticket={data} />
                ) : (
                  <section className={styles.requestCard}>
                    <h3>업무 메타데이터</h3>
                    <p>관리 권한에서 허용된 배정·상태 정보예요.</p>
                  </section>
                )
              ) : hasRequest(data) && !admin && tab === 'work' ? (
                <>
                  <RequestReference ticket={data} />
                  <WorkRecordEditor ticket={data} />
                  <section className={styles.requestCard}>
                    <h3>문의 처리 기록 이력</h3>
                    {data.workRecords.length ? (
                      [...data.workRecords].reverse().map((record) => (
                        <article key={record.id} className={workflow.record}>
                          <strong>
                            {Object.hasOwn(workKinds, record.kind)
                              ? Object.entries(workKinds).find(
                                  ([kind]) => kind === record.kind,
                                )?.[1]
                              : '기록 유형 확인 필요'}{' '}
                            · {record.author.name}
                          </strong>
                          <p>{record.action}</p>
                          {record.verification && <p>동작 확인: {record.verification}</p>}
                          {(['VERIFIED_RESOLUTION', 'IMPLEMENTED_VERIFIED'].includes(record.kind) ||
                            record.confirmedCause !== null ||
                            record.appliesToSystemVersion !== null) && (
                            <p>
                              확인된 원인: {record.confirmedCause ?? '미확인'} · 적용 버전:{' '}
                              {record.appliesToSystemVersion ?? '미확인'}
                            </p>
                          )}
                          <small>{displayDate(record.createdAt)}</small>
                        </article>
                      ))
                    ) : (
                      <p>아직 저장된 문의 처리 기록이 없어요.</p>
                    )}
                  </section>
                  {data.activeResolutionRequest && (
                    <section className={styles.requestCard}>
                      <h3>문의자 해결 확인</h3>
                      <p>{data.activeResolutionRequest.resultSummary}</p>
                      <p>
                        {data.activeResolutionRequest.state === 'PENDING'
                          ? '문의자 응답 대기'
                          : data.activeResolutionRequest.state === 'RESOLVED'
                            ? '문의자가 해결을 확인했어요.'
                            : data.activeResolutionRequest.state === 'UNRESOLVED'
                              ? '미해결 응답으로 같은 티켓의 처리를 이어가요.'
                              : '상태 확인 필요'}
                      </p>
                    </section>
                  )}
                  <CaseGenerationPanel generation={data.caseGeneration} />
                </>
              ) : hasRequest(data) && !admin && tab === 'case' ? (
                <CaseGenerationPanel generation={data.caseGeneration} />
              ) : (
                <ErrorState title="지원하지 않는 상세 탭이에요" />
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
              {canCorrect && (
                <div className={styles.detailActions}>
                  <Button
                    id={'correct-assignment-' + id}
                    variant="secondary"
                    onClick={() => {
                      setCorrection('assignment');
                    }}
                  >
                    책임 시스템 정정
                  </Button>
                  {!admin &&
                    staff &&
                    ['INVESTIGATION', 'INCIDENT'].includes(data.processingType) && (
                      <Button
                        id={'correct-processing-' + id}
                        variant="secondary"
                        onClick={() => {
                          setCorrection('processing');
                        }}
                      >
                        처리 유형 정정
                      </Button>
                    )}
                </div>
              )}
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
          {correction && (
            <TicketCorrection
              ticket={data}
              kind={correction}
              onClose={() => {
                setCorrection(null);
              }}
            />
          )}
        </>
      )
    );
  return (
    <RouteModal
      title={heading}
      titleHint={shownTicket?.ticketNumber}
      onClose={onClose}
      returnFocusId={returnFocusId ?? 'ticket-' + id}
      padded={false}
      footer={null}
    >
      {/* 모달이 바깥의 전역 안내를 막는다. 보드 구독이 끊겨도 상세 안에서 알리고 다시 연결한다. */}
      {allowed && !subscription.denied && !(!admin && subscription.state === 'disconnected') && (
        <ReconnectNotice inline />
      )}
      {content}
    </RouteModal>
  );
}
