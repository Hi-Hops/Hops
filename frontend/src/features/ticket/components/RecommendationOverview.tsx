import type { AssistanceMaterial } from '@/shared/api/types/hopsSystem1';
import { conditionValue, isNarrativeCondition } from '../model/conditionPresentation';
import styles from './RecommendationOverview.module.css';

const verdicts = {
  MATCHED: ['✓', '일치'],
  UNKNOWN: ['', '미확인'],
  MISMATCHED: ['!', '불일치'],
} as const;
const rankLabel = (rank: number | null | undefined) => (rank == null ? '미제공' : `${rank}위`);

/** 검색의 의미 유사도와 업무 적용 조건을 각각의 실제 척도로 보여준다. */
export function RecommendationOverview({
  item,
  onCompare,
}: {
  item: AssistanceMaterial;
  onCompare?: () => void;
}) {
  const { evidence, assessment } = item;
  const narrativeOnly =
    assessment.conditions.length > 0 && assessment.conditions.every(isNarrativeCondition);
  const ranking = evidence.recommendation;
  const similarity = ranking?.semanticSimilarity ?? null;
  const bounded = similarity === null ? null : Math.max(-1, Math.min(1, similarity));
  const position = bounded === null ? 0 : (bounded + 1) * 50;
  return (
    <section className={styles.overview} aria-label="추천 시각화">
      <div className={styles.similarity}>
        <h5>RAG 의미 유사도</h5>
        <strong className={styles.score}>
          {similarity === null ? '미제공' : similarity.toFixed(3)}
        </strong>
        {similarity !== null && (
          <>
            <div
              className={styles.scale}
              role="meter"
              aria-label="RAG 의미 유사도"
              aria-valuemin={-1}
              aria-valuemax={1}
              aria-valuenow={bounded ?? undefined}
              aria-valuetext={`${similarity.toFixed(3)}, -1에서 1 사이의 의미 유사도`}
            >
              <span className={styles.zero} />
              <span
                className={styles.fill}
                style={{
                  left: `${Math.min(50, position)}%`,
                  width: `${Math.abs(bounded ?? 0) * 50}%`,
                }}
              />
              <span className={styles.point} style={{ left: `${position}%` }} />
            </div>
            <div className={styles.axis} aria-hidden="true">
              <span>-1</span>
              <span>0</span>
              <span>1</span>
            </div>
          </>
        )}
        <p className={styles.explanation}>
          {similarity === null
            ? '의미 검색 점수가 제공되지 않았어요.'
            : '높을수록 현재 문의와 내용이 비슷해요.'}
        </p>
        <p className={styles.caution}>해결 확률을 뜻하지 않습니다.</p>
        <dl className={styles.rankings}>
          <div>
            <dt>키워드 검색</dt>
            <dd>{rankLabel(ranking?.keywordRank)}</dd>
          </div>
          <div>
            <dt>의미 검색</dt>
            <dd>{rankLabel(ranking?.semanticRank)}</dd>
          </div>
          <div>
            <dt>통합 검색 · RRF</dt>
            <dd>{ranking?.score.toFixed(4) ?? '미제공'}</dd>
          </div>
        </dl>
      </div>
      <div className={styles.applicability}>
        <header>
          <h5>적용 조건</h5>
          <strong data-manual={narrativeOnly}>
            {narrativeOnly ? (
              '직접 대조 필요'
            ) : assessment.conditions.length > 0 ? (
              <>
                {assessment.matched}
                <span> / {assessment.conditions.length}개 일치</span>
              </>
            ) : (
              '대조 필요'
            )}
          </strong>
        </header>
        {narrativeOnly ? (
          <p className={styles.manualHint}>
            문장으로 작성된 적용 조건은 원문을 직접 대조해 주세요.
          </p>
        ) : (
          <>
            <p className={styles.counts}>
              <span>일치 {assessment.matched}</span> · 미확인 {assessment.unknown}
              {assessment.mismatched > 0 && (
                <>
                  {' '}
                  · <span className={styles.conflict}>불일치 {assessment.mismatched}</span>
                </>
              )}
            </p>
          </>
        )}
        {assessment.conditions.length > 0 ? (
          <ul className={styles.conditions} aria-label="조건별 일치 현황">
            {assessment.conditions.map((c) => (
              <li key={c.key} data-status={c.status} data-narrative={isNarrativeCondition(c)}>
                <div>
                  <strong>{isNarrativeCondition(c) ? '서술형 적용 조건' : c.label}</strong>
                  <span className={styles.verdict}>
                    {verdicts[c.status][0] && (
                      <span aria-hidden="true">{verdicts[c.status][0]}</span>
                    )}{' '}
                    {verdicts[c.status][1]}
                  </span>
                </div>
                {isNarrativeCondition(c) ? (
                  <>
                    <p className={styles.narrativePreview} title={c.caseEvidence}>
                      {conditionValue(c)}
                    </p>
                    {onCompare && (
                      <button type="button" className={styles.compareButton} onClick={onCompare}>
                        비교 근거 보기 →
                      </button>
                    )}
                  </>
                ) : (
                  <p title={c.currentEvidence.join(', ')}>
                    {c.currentEvidence.length
                      ? c.currentEvidence.join(', ')
                      : `사례 조건: ${conditionValue(c)}`}
                  </p>
                )}
              </li>
            ))}
          </ul>
        ) : (
          <p className={styles.explanation}>
            비교할 구체적인 조건이 제공되지 않았어요. 자료 원문을 확인해 주세요.
          </p>
        )}
      </div>
    </section>
  );
}
