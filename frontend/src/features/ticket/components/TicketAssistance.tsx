import { useEffect, useId, useRef, useState } from 'react';
import { Tabs } from 'radix-ui';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { Button } from '@/shared/ui/Button';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { MarkdownText } from '@/shared/ui/MarkdownText';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import type { AssistanceMaterial, TicketDetail } from '@/shared/api/types/hopsSystem1';
import { getTicketAssistance } from '../api/systemTicketApi';
import { RecommendationOverview } from './RecommendationOverview';
import { GroupedCases } from './GroupedCases';
import { displayTicketNumber } from '@/shared/lib/ticketNumber';
import { displayDate } from '@/shared/lib/date';
import { comparisonLabels } from '../model/recommendation';
import {
  conditionValue,
  distinctStatements,
  isNarrativeCondition,
  statementParts,
} from '../model/conditionPresentation';
import styles from './Assistance.module.css';
const categories = [
  ['CASE', '해결 사례'],
  ['GUIDE', '매뉴얼'],
  ['RUNBOOK', '런북'],
  ['POLICY', '정책'],
] as const;
const bands = {
  HIGH: ['조건 일치 높음', 'neutral'],
  MEDIUM: ['일부 조건 일치', 'neutral'],
  REFERENCE: ['조건 확인 필요', 'neutral'],
  CONFLICT: ['조건 다름 · 적용 주의', 'danger'],
} as const;
function Highlight({ text, values }: { text: string; values: string[] }) {
  const terms = [...new Set(values)]
    .filter((v) => v && text.toLocaleLowerCase().includes(v.toLocaleLowerCase()))
    .sort((a, b) => b.length - a.length);
  if (!terms.length) return <>{text}</>;
  const pattern = new RegExp(
    `(${terms.map((term) => term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`,
    'giu',
  );
  return (
    <>
      {text
        .split(pattern)
        .map((part, index) =>
          terms.some((term) => term.toLocaleLowerCase() === part.toLocaleLowerCase()) ? (
            <mark key={index}>{part}</mark>
          ) : (
            part
          ),
        )}
    </>
  );
}
function ConditionEvidence({ item }: { item: AssistanceMaterial }) {
  const { evidence, assessment } = item;
  const [activePair, setActivePair] = useState(0);
  const narrative = assessment.conditions.filter(isNarrativeCondition);
  const structured = assessment.conditions.filter((c) => !isNarrativeCondition(c));
  const pair = assessment.quotePairs[activePair];
  const current = pair
    ? (assessment.conditions.find((c) => c.label === pair.label)?.currentEvidence ?? [])
    : [];
  const aliases =
    pair?.label === '기안자' && current.includes('본인')
      ? ['제가', '직접', '본인']
      : pair?.label === '조회 기간'
        ? ['조회 기간', '기안일', '포함', ...current]
        : current;
  const sourceValues = pair
    ? [
        pair.sourceQuote.includes(':')
          ? pair.sourceQuote.slice(pair.sourceQuote.indexOf(':') + 1).trim()
          : pair.sourceQuote,
      ]
    : [];
  return (
    <div className={styles.comparisonPanel}>
      <div className={styles.evidenceBody}>
        <ul className={styles.reasons}>
          {assessment.reasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
        {pair && (
          <section aria-label="문의와 근거 원문 비교" className={styles.compare}>
            <h4>원문에서 확인</h4>
            <div className={styles.pairTabs}>
              {assessment.quotePairs.map((p, index) => (
                <button
                  type="button"
                  key={p.label}
                  aria-pressed={activePair === index}
                  onClick={() => {
                    setActivePair(index);
                  }}
                >
                  {p.label}
                </button>
              ))}
            </div>
            <div className={styles.quotes} data-status={pair.status}>
              <div>
                <small>문의자 원문</small>
                <blockquote>
                  <Highlight text={pair.currentQuote} values={aliases} />
                </blockquote>
              </div>
              <div>
                <small>자료 원문 · v{evidence.documentVersion}</small>
                <blockquote>
                  <Highlight text={pair.sourceQuote} values={sourceValues} />
                </blockquote>
              </div>
            </div>
          </section>
        )}
        {structured.length > 0 && (
          <div className={styles.tableWrap}>
            <table className={styles.conditionTable}>
              <caption>적용 조건 비교</caption>
              <thead>
                <tr>
                  <th scope="col">조건</th>
                  <th scope="col">현재 문의</th>
                  <th scope="col">자료의 적용 조건</th>
                </tr>
              </thead>
              <tbody>
                {structured.map((c) => (
                  <tr key={c.key} data-active={pair?.label === c.label}>
                    <th scope="row">
                      {c.label}
                      <small data-status={c.status}>{comparisonLabels[c.status]}</small>
                    </th>
                    <td>{c.currentEvidence.join(', ') || '미확인'}</td>
                    <td>
                      {c.caseEvidence.startsWith(c.label + ':')
                        ? c.caseEvidence.slice(c.label.length + 1).trim()
                        : c.caseEvidence}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {narrative.map((condition) => {
          const statements = distinctStatements(condition.currentEvidence);
          return (
            <section
              className={styles.manualComparison}
              key={condition.key}
              aria-label="서술형 적용 조건 비교"
            >
              <h4>직접 대조가 필요한 조건</h4>
              <p>
                자동 일치 판정 없이, 현재 문의의 확인 내용과 사례의 적용 조건을 나란히 보여드립니다.
              </p>
              <div className={styles.manualColumns}>
                <div>
                  <h5>현재 문의에서 확인된 내용</h5>
                  {statements.length ? (
                    <ul>
                      {statements.map((text, index) => {
                        const parts = statementParts(text);
                        return (
                          <li key={index}>
                            {parts ? (
                              <>
                                <span>{parts.label}</span>
                                <p>{parts.value}</p>
                              </>
                            ) : (
                              <p>{text}</p>
                            )}
                          </li>
                        );
                      })}
                    </ul>
                  ) : (
                    <p>공개 가능한 확인 내용이 없습니다.</p>
                  )}
                </div>
                <div>
                  <h5>사례의 적용 조건</h5>
                  <MarkdownText text={conditionValue(condition)} />
                </div>
              </div>
            </section>
          );
        })}
        <p className={styles.caption}>
          조건 표시는 확인된 적용 조건의 대조 결과이며, 해결 확률이 아닙니다.
        </p>
      </div>
    </div>
  );
}
function MaterialDetail({
  item,
  position,
  groupedMember = false,
  group,
  onGroupSelect,
  onChoose,
}: {
  item: AssistanceMaterial;
  position: number;
  groupedMember?: boolean;
  group?: AssistanceMaterial;
  onGroupSelect?: (id: string) => void;
  onChoose: () => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const comparison = useRef<HTMLDivElement>(null);
  const [detailTab, setDetailTab] = useState('actions');
  useEffect(() => {
    if (detailTab === 'compare') {
      comparison.current?.focus({ preventScroll: true });
      comparison.current?.scrollIntoView({ block: 'start', behavior: 'auto' });
    }
  }, [detailTab]);
  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
    heading.current?.closest('article')?.scrollIntoView({ block: 'start', behavior: 'auto' });
  }, []);
  const { evidence, assessment } = item;
  const band = bands[assessment.relevance];
  const important = assessment.sections.filter((s) =>
    ['실제 조치', '검증 결과', '절차', '확인 항목', '결과 해석', '규칙'].includes(s.label),
  );
  const pending = assessment.conditions.filter((c) => c.status !== 'MATCHED');
  return (
    <Tabs.Root value={detailTab} onValueChange={setDetailTab} asChild>
      <article className={styles.materialDetail} aria-label={evidence.title}>
        <div className={styles.materialHeading}>
          <div className={styles.materialMeta}>
            <span>
              {groupedMember
                ? `${position}위 대표 그룹 · 묶인 사례`
                : `선택한 자료 · ${position}위`}
            </span>
            <StatusBadge label={band[0]} tone={band[1]} />
            <Button variant="ghost" onClick={onChoose}>
              ↑ 자료 다시 선택
            </Button>
          </div>
          <h4 ref={heading} tabIndex={-1}>
            {evidence.title}
          </h4>
          {item.sourceCase && (
            <p className={styles.sourceMeta}>
              {item.sourceCase.caseNumber} · 승인 v{evidence.documentVersion} · 원 티켓{' '}
              {displayTicketNumber(item.sourceCase.ticketNumber)} · 승인{' '}
              {displayDate(item.sourceCase.approvedAt)}
            </p>
          )}
        </div>
        {group && onGroupSelect && (
          <GroupedCases
            cases={group.groupedCases ?? []}
            representativeId={group.evidence.id}
            selectedId={evidence.id}
            onSelect={onGroupSelect}
          />
        )}
        <RecommendationOverview
          item={item}
          onCompare={() => {
            if (detailTab === 'compare')
              comparison.current?.scrollIntoView({ block: 'start', behavior: 'auto' });
            else setDetailTab('compare');
          }}
        />
        {pending.length > 0 && (
          <div className={styles.preconditions} data-conflict={assessment.mismatched > 0}>
            <strong>{assessment.mismatched > 0 ? '그대로 적용하지 마세요' : '적용 전 확인'}</strong>
            <span>
              {pending
                .map((c) => (isNarrativeCondition(c) ? '서술형 적용 조건' : c.label))
                .join(' · ')}
            </span>
          </div>
        )}
        <Tabs.List className={styles.detailTabs} aria-label="선택 자료 상세">
          <Tabs.Trigger value="actions">조치·검증</Tabs.Trigger>
          <Tabs.Trigger value="compare">비교 근거</Tabs.Trigger>
          <Tabs.Trigger value="source">원문·검색 점수</Tabs.Trigger>
        </Tabs.List>
        <Tabs.Content value="actions">
          {important.length > 0 ? (
            <section
              className={styles.actions}
              aria-label={evidence.kind === 'CASE' ? '참고할 조치와 검증' : '참고할 절차'}
            >
              {important.map((s) => (
                <div key={s.start} data-verification={s.label === '검증 결과'}>
                  <h5>
                    {s.label === '실제 조치'
                      ? '참고할 조치'
                      : s.label === '검증 결과'
                        ? '과거 검증 결과'
                        : s.label}
                  </h5>
                  <MarkdownText text={s.quote} />
                </div>
              ))}
            </section>
          ) : (
            <p className={styles.noAction}>
              확인된 조치 요약이 없습니다. 자료 원문과 적용 조건을 확인해 주세요.
            </p>
          )}
        </Tabs.Content>
        <Tabs.Content value="compare" ref={comparison}>
          <ConditionEvidence item={item} />
        </Tabs.Content>
        <Tabs.Content value="source">
          <div className={styles.evidenceBody}>
            <p className={styles.sourceMeta}>
              {evidence.origin === 'SYNTHETIC'
                ? '합성 시연 자료'
                : evidence.origin === 'DEMO_VERIFIED'
                  ? '시연에서 검증한 자료'
                  : '운영 자료'}{' '}
              · 승인 문서 v{evidence.documentVersion} · 적용 시스템 버전{' '}
              {evidence.appliesToSystemVersion ?? '미확인'}
            </p>
            <MarkdownText text={evidence.excerpt} />
            {evidence.recommendation && (
              <dl className={styles.searchScores}>
                <div>
                  <dt>의미 검색 유사도</dt>
                  <dd>{evidence.recommendation.semanticSimilarity?.toFixed(3) ?? '미제공'}</dd>
                </div>
                <div>
                  <dt>키워드 검색 순위</dt>
                  <dd>
                    {evidence.recommendation.keywordRank === null
                      ? '후보 없음'
                      : `${evidence.recommendation.keywordRank}위`}
                  </dd>
                </div>
                <div>
                  <dt>의미 검색 순위</dt>
                  <dd>
                    {evidence.recommendation.semanticRank === null
                      ? '후보 없음'
                      : `${evidence.recommendation.semanticRank}위`}
                  </dd>
                </div>
                <div>
                  <dt>통합 검색 점수 · RRF</dt>
                  <dd>{evidence.recommendation.score.toFixed(4)}</dd>
                </div>
              </dl>
            )}
            <p className={styles.caption}>
              검색 점수는 자료를 찾는 기준입니다. 현재 문의에 적용할 수 있는지는 위 조건을 함께
              확인해 주세요.
            </p>
          </div>
        </Tabs.Content>
        <p className={styles.sourceFoot}>
          {evidence.origin === 'SYNTHETIC' ? '합성 시연 자료 · ' : ''}승인 문서 v
          {evidence.documentVersion}
        </p>
      </article>
    </Tabs.Root>
  );
}
export function TicketAssistance({ ticket }: { ticket: TicketDetail }) {
  const actor = useSession((s) => s.user?.id);
  const detailId = useId();
  const cards = useRef<HTMLOListElement>(null);
  const [kind, setKind] = useState<string>('CASE');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [groupedId, setGroupedId] = useState<string | null>(null);
  const query = useQuery({
    queryKey: [
      'hops-system-1',
      'tickets',
      'assistance',
      actor,
      ticket.id,
      ticket.responsibleSystem.id,
      ticket.standardRequest.id,
    ],
    queryFn: ({ signal }) => getTicketAssistance(ticket.id, signal),
    enabled: Boolean(actor),
    retry: false,
    staleTime: 60000,
    refetchOnWindowFocus: false,
  });
  const materials = query.data?.materials.filter((m) => m.evidence.kind === kind) ?? [];
  const selected = materials.find((m) => m.evidence.id === selectedId);
  const active = selected?.groupedCases?.find((m) => m.evidence.id === groupedId) ?? selected;
  return (
    <Tabs.Root
      value={kind}
      onValueChange={(value) => {
        setKind(value);
        setSelectedId(null);
        setGroupedId(null);
      }}
      asChild
    >
      <section className={styles.workspace} aria-label="티켓 해결 근거">
        <header className={styles.header}>
          <h3>추천 해결 자료</h3>
          <Button
            variant="ghost"
            disabled={query.isFetching}
            onClick={() => {
              void query.refetch();
            }}
          >
            다시 찾기
          </Button>
        </header>
        <Tabs.List className={styles.categories} aria-label="참고 자료 유형">
          {categories.map(([value, label]) => (
            <Tabs.Trigger
              key={value}
              value={value}
              aria-label={`${label} ${query.data?.materials.filter((m) => m.evidence.kind === value).length ?? 0}`}
            >
              {label}
              <span>
                {query.data?.materials.filter((m) => m.evidence.kind === value).length ?? 0}
              </span>
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        {query.isPending ? (
          <LoadingState label="문의 조건과 관련된 해결 자료를 찾고 있어요" />
        ) : query.isError ? (
          <ErrorState
            title="참고 자료를 불러오지 못했어요"
            onRetry={() => {
              void query.refetch();
            }}
          />
        ) : (
          <Tabs.Content value={kind}>
            {!materials.length ? (
              <div className={styles.empty}>
                <strong>현재 조건과 맞는 자료를 찾지 못했어요.</strong>
                <p>다른 유형의 자료를 확인하거나 문의의 미확인 조건을 확인해 주세요.</p>
              </div>
            ) : (
              <>
                <ol ref={cards} className={styles.rankCards} aria-label="추천순 자료">
                  {materials.map((m, index) => (
                    <li key={m.evidence.id}>
                      <button
                        type="button"
                        className={styles.rankCard}
                        aria-label={`${index + 1}위 ${m.evidence.title}`}
                        aria-pressed={selectedId === m.evidence.id}
                        aria-controls={detailId}
                        onClick={() => {
                          setSelectedId(m.evidence.id);
                          setGroupedId(null);
                        }}
                      >
                        <span className={styles.cardTop}>
                          <span className={styles.cardRank}>
                            {String(index + 1).padStart(2, '0')}
                          </span>
                          <span>
                            {selectedId === m.evidence.id
                              ? '선택됨'
                              : m.evidence.origin === 'SYNTHETIC'
                                ? '시연 자료'
                                : '승인 자료'}
                          </span>
                        </span>
                        <strong title={m.evidence.title}>
                          {m.evidence.origin === 'SYNTHETIC'
                            ? m.evidence.title.split(' — ')[0]
                            : m.evidence.title}
                        </strong>
                        <span className={styles.cardSimilarity}>
                          의미 유사도{' '}
                          <b>
                            {m.evidence.recommendation?.semanticSimilarity?.toFixed(3) ?? '미제공'}
                          </b>
                        </span>
                        <span className={styles.cardCounts}>
                          일치 <b>{m.assessment.matched}</b> · 미확인 {m.assessment.unknown}
                          {m.assessment.mismatched > 0 && (
                            <span className={styles.conflictCount}>
                              {' '}
                              · 불일치 {m.assessment.mismatched}
                            </span>
                          )}
                        </span>
                        {(m.groupedCases?.length ?? 0) > 1 && (
                          <span className={styles.cardCounts}>
                            묶인 사례 {m.groupedCases?.length}건
                          </span>
                        )}
                      </button>
                    </li>
                  ))}
                </ol>
                <div id={detailId}>
                  {selected && active ? (
                    <MaterialDetail
                      key={active.evidence.id}
                      item={active}
                      groupedMember={active.evidence.id !== selected.evidence.id}
                      group={selected}
                      onGroupSelect={setGroupedId}
                      position={materials.indexOf(selected) + 1}
                      onChoose={() => {
                        cards.current?.scrollIntoView({ block: 'start', behavior: 'auto' });
                        cards.current
                          ?.querySelector<HTMLButtonElement>('button[aria-pressed="true"]')
                          ?.focus({ preventScroll: true });
                      }}
                    />
                  ) : (
                    <p className={styles.selectionHint}>
                      자료 카드를 선택하면 조치와 비교 근거를 확인할 수 있어요.
                    </p>
                  )}
                </div>
              </>
            )}
          </Tabs.Content>
        )}
      </section>
    </Tabs.Root>
  );
}
