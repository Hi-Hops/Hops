import type { ReactNode } from 'react';
import type { RecommendedCase } from '@/shared/api/types/hopsSystem1';
import { MarkdownText } from '@/shared/ui/MarkdownText';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { comparisonLabels, comparisonTones } from '../model/recommendation';
import styles from './Tickets.module.css';

export function RecommendedCaseCard({
  item,
  rank,
  metadata,
}: {
  item: RecommendedCase;
  rank: number;
  metadata: ReactNode;
}) {
  const explanation = item.recommendation;
  return (
    <details className={styles.recommendation}>
      <summary>
        <span className={styles.recommendationRank} aria-hidden="true">
          {rank}
        </span>
        <span className={styles.recommendationCopy}>
          <span className={styles.recommendationTitle}>
            <strong>{item.title}</strong>
            <small>문서 v{item.documentVersion}</small>
            {explanation && (
              <small className={styles.recommendationScore}>
                검색 관련도 {explanation.score.toFixed(4)}
              </small>
            )}
          </span>
          <span className={styles.recommendationReason}>
            <strong>추천 이유</strong>{' '}
            {explanation
              ? explanation.reasons.join(' ')
              : '검색 근거가 제공되지 않았어요. 적용 조건을 직접 확인해 주세요.'}
          </span>
          <span className={styles.applicability}>
            <strong>적용 조건</strong> {item.applicability || '사례 본문에서 확인해 주세요.'}
          </span>
        </span>
        <span className={styles.recommendationToggle} aria-hidden="true" />
      </summary>
      <div className={styles.recommendationBody}>
        {explanation && (
          <>
            <section aria-label="검색 점수 안내" className={styles.recommendationMethod}>
              <strong>검색 점수 안내</strong>
              <p>
                키워드 검색과 의미 검색의 순위를 결합한 점수예요. 해결 확률이나 적용 조건의 일치율을
                뜻하지 않아요.
              </p>
              <dl>
                <dt>검색에 사용한 티켓 제목</dt>
                <dd>{explanation.query}</dd>
                <dt>키워드 검색 순위</dt>
                <dd>
                  {explanation.keywordRank === null
                    ? '해당 검색 경로의 후보에 없음'
                    : `${explanation.keywordRank}위 · 원점수 ${explanation.keywordScore}`}
                </dd>
                <dt>의미 검색 순위</dt>
                <dd>
                  {explanation.semanticRank === null
                    ? '해당 검색 경로의 후보에 없음'
                    : `${explanation.semanticRank}위 · 코사인 유사도 ${explanation.semanticSimilarity?.toFixed(4)}`}
                </dd>
              </dl>
            </section>
            <section aria-label="현재 문의와 적용 조건 비교">
              <h4>현재 문의와 적용 조건 비교</h4>
              <p className={styles.sectionHint}>
                확인 사실과 승인된 사례 조건을 대조했어요. 자유서술 조건과 미확인 사항은 적용 전에
                직접 확인해 주세요.
              </p>
              {explanation.comparisons.some((item) => item.status === 'MISMATCHED') && (
                <p className={styles.comparisonWarning}>
                  불일치 조건이 있어요. 이 사례를 그대로 적용하지 말고 조건을 먼저 확인해 주세요.
                </p>
              )}
              <ul className={styles.conditionComparisons}>
                {explanation.comparisons.map((comparison) => (
                  <li key={comparison.key}>
                    <div className={styles.comparisonHeading}>
                      <strong>{comparison.label}</strong>
                      <StatusBadge
                        label={comparisonLabels[comparison.status]}
                        tone={comparisonTones[comparison.status]}
                      />
                    </div>
                    <dl>
                      <dt>현재 문의</dt>
                      <dd>
                        {comparison.currentEvidence.length
                          ? comparison.currentEvidence.join('\n')
                          : '확인된 정보가 없어요.'}
                      </dd>
                      <dt>사례 조건</dt>
                      <dd>{comparison.caseEvidence}</dd>
                    </dl>
                  </li>
                ))}
              </ul>
            </section>
          </>
        )}
        <strong>승인된 사례 원문 · 문서 v{item.documentVersion}</strong>
        <MarkdownText text={item.excerpt} />
        {metadata}
      </div>
    </details>
  );
}
