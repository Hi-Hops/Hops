import { Link } from 'react-router';
import type { AssistanceCase } from '@/shared/api/types/hopsSystem1';
import { paths } from '@/shared/config/paths';
import { displayDate } from '@/shared/lib/date';
import { displayTicketNumber } from '@/shared/lib/ticketNumber';
import styles from './Assistance.module.css';

export function GroupedCases({
  cases,
  representativeId,
  selectedId,
  onSelect,
}: {
  cases: AssistanceCase[];
  representativeId: string;
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  if (cases.length < 2) return null;
  return (
    <details
      className={styles.groupedCases}
      open={selectedId !== null && selectedId !== representativeId}
    >
      <summary>묶인 사례 {cases.length}건 펼쳐보기</summary>
      <p>
        같은 해결책으로 검색된 승인 사례입니다. 선택하면 각 사례의 조치·조건·검색 점수를 확인할 수
        있어요.
      </p>
      <ul aria-label="묶인 해결 사례 목록">
        {cases.map(({ evidence, assessment, sourceCase }) => (
          <li key={evidence.id}>
            <button
              type="button"
              aria-pressed={selectedId === evidence.id}
              onClick={() => {
                onSelect(evidence.id);
              }}
              aria-label={`묶인 사례 상세 보기: ${evidence.title}`}
            >
              <span className={styles.groupedCaseMeta}>
                <span>
                  {sourceCase?.caseNumber ?? '승인 사례'} · v{evidence.documentVersion}
                </span>
                {evidence.id === representativeId && <b>대표</b>}
              </span>
              <strong>{evidence.title}</strong>
              <span className={styles.groupedCaseMeta}>
                <span>
                  의미 유사도 {evidence.recommendation?.semanticSimilarity?.toFixed(3) ?? '미제공'}
                </span>
                <span>
                  일치 {assessment.matched} · 미확인 {assessment.unknown}
                </span>
              </span>
              {sourceCase && (
                <span className={styles.groupedCaseMeta}>
                  원 티켓 {displayTicketNumber(sourceCase.ticketNumber)} · 승인{' '}
                  {displayDate(sourceCase.approvedAt)}
                </span>
              )}
            </button>
            <Link to={paths.case(evidence.documentId)} target="_blank" rel="noopener noreferrer">
              승인 사례 열기<span className={styles.srOnly}>: {evidence.title} (새 탭)</span> ↗
            </Link>
          </li>
        ))}
      </ul>
    </details>
  );
}
