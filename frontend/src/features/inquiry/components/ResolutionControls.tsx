import { useSession } from '@/features/auth';
import { useSystemCommand } from '@/shared/api/useSystemCommand';
import { Button } from '@/shared/ui/Button';
import { MarkdownText } from '@/shared/ui/MarkdownText';
import type { ResolutionRequest } from '@/shared/api/types/hopsSystem1';
import styles from './ResultPanel.module.css';
import { resolutionResponseCommand } from '../api/systemInquiryApi';

type ResolutionControlsProps = {
  request: ResolutionRequest;
  problemTitle?: string | undefined;
  guideInChat?: boolean;
};
export function ResolutionControls({
  request,
  problemTitle,
  guideInChat = false,
}: ResolutionControlsProps) {
  const actor = useSession((s) => s.user?.id ?? '');
  const command = useSystemCommand(actor, 'resolution:' + request.id);
  const knownSource = ['GUIDE', 'WORK_RECORD'].includes(request.source);
  const pending = request.state === 'PENDING' && knownSource;
  const respond = (decision: 'RESOLVED' | 'UNRESOLVED') => {
    void command.execute((requestId) =>
      resolutionResponseCommand(request.id, {
        requestId,
        expectedRequestRevision: request.requestRevision,
        decision,
      }),
    );
  };
  const guideDetails = (
    <details className={styles.excerpt}>
      <summary>안내 다시 보기</summary>
      <MarkdownText text={request.resultSummary} />
    </details>
  );
  const synthetic = request.confirmationOrigin === 'SYNTHETIC_PRESET';

  if (request.state === 'RESOLVED' && !command.pending) {
    return (
      <section className={styles.completedResolution} aria-label="문제 해결 결과">
        <span className={styles.completedIcon} aria-hidden="true">
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
            <path
              d="m4 9 3.3 3.3L14 5.7"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </span>
        <div className={styles.completedContent}>
          <div role="status">
            <strong>문제가 해결됐어요</strong>
            {problemTitle && <p className={styles.completedTitle}>{problemTitle}</p>}
          </div>
          {request.source === 'GUIDE' ? (
            !guideInChat && guideDetails
          ) : (
            <p className={styles.resultSummary}>{request.resultSummary}</p>
          )}
          {synthetic && <small className={styles.meta}>가상 확인 시나리오</small>}
        </div>
      </section>
    );
  }

  return (
    <section className={styles.confirmation} aria-label="문제 해결 확인">
      {problemTitle && <span className={styles.meta}>{problemTitle}</span>}
      <strong>
        {request.state === 'RESOLVED'
          ? '해결 완료'
          : request.state === 'UNRESOLVED'
            ? '미해결 응답 저장'
            : '이 문제가 해결됐나요?'}
      </strong>
      {request.source === 'GUIDE' ? (
        // 안내 본문은 채팅에 이미 있으므로 여기서는 접어 둔다
        <>
          {pending && <p>채팅에서 받은 안내로 해결됐는지 알려 주세요.</p>}
          {guideDetails}
        </>
      ) : (
        // 담당자가 직접 쓴 처리 요약은 AI 답변이 아니라서 서식으로 해석하지 않는다(담당자 티켓 화면과 같게)
        <p className={styles.resultSummary}>{request.resultSummary}</p>
      )}
      {synthetic && <small className={styles.meta}>가상 확인 시나리오</small>}
      {pending || command.pending ? (
        <>
          <div className={styles.confirmActions}>
            {command.pending ? (
              <Button
                variant="secondary"
                disabled={command.busy || command.expired || command.remaining > 0}
                onClick={() => {
                  respond('RESOLVED');
                }}
              >
                {command.label ?? '같은 요청 결과 확인'}
              </Button>
            ) : (
              <>
                <Button
                  disabled={command.busy || command.remaining > 0}
                  onClick={() => {
                    respond('RESOLVED');
                  }}
                >
                  해결됐어요
                </Button>
                <Button
                  variant="secondary"
                  disabled={command.busy || command.remaining > 0}
                  onClick={() => {
                    respond('UNRESOLVED');
                  }}
                >
                  아직 해결되지 않았어요
                </Button>
              </>
            )}
          </div>
        </>
      ) : (
        <p role="status">
          {request.state === 'UNRESOLVED'
            ? '미해결 응답을 저장했어요. 같은 문제의 처리를 이어가요.'
            : '확인 상태를 다시 조회해 주세요.'}
        </p>
      )}
      {command.error && <p role="alert">{command.error}</p>}
      {pending && command.notice && <p role="status">{command.notice}</p>}
      {command.remaining > 0 && (
        <p role="status">{command.remaining}초 후 다시 요청할 수 있어요.</p>
      )}
    </section>
  );
}
