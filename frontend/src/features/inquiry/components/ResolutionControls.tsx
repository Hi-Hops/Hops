import { useSession } from '@/features/auth';
import { useSystemCommand } from '@/shared/api/useSystemCommand';
import { Button } from '@/shared/ui/Button';
import { MarkdownText } from '@/shared/ui/MarkdownText';
import type { ResolutionRequest } from '@/shared/api/types/hopsSystem1';
import styles from './ResultPanel.module.css';
import { resolutionResponseCommand } from '../api/systemInquiryApi';

type ResolutionControlsProps = { request: ResolutionRequest };
export function ResolutionControls({ request }: ResolutionControlsProps) {
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
  return (
    <section className={styles.confirmation} aria-label="문제 해결 확인">
      <strong>이 문제가 해결됐나요?</strong>
      {request.source === 'GUIDE' ? (
        // 안내 본문은 채팅에 이미 있으므로 여기서는 접어 둔다
        <>
          {pending && <p>채팅에서 받은 안내로 해결됐는지 알려 주세요.</p>}
          <details className={styles.excerpt}>
            <summary>안내 다시 보기</summary>
            <MarkdownText text={request.resultSummary} />
          </details>
        </>
      ) : (
        // 담당자가 직접 쓴 처리 요약은 AI 답변이 아니라서 서식으로 해석하지 않는다(담당자 티켓 화면과 같게)
        <p>{request.resultSummary}</p>
      )}
      <p className={styles.meta}>
        {request.source === 'GUIDE'
          ? '저장된 안내'
          : request.source === 'WORK_RECORD'
            ? '담당자 문의 처리 기록'
            : '확인 출처 미확인'}{' '}
        ·
        {request.confirmationOrigin === 'SYNTHETIC_PRESET'
          ? '가상 확인 시나리오'
          : request.confirmationOrigin === 'HOPS_USER'
            ? 'Hops 문의자 확인'
            : '확인 경로 미확인'}
      </p>
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
          {request.state === 'RESOLVED'
            ? '해결됐다는 응답을 저장했어요.'
            : request.state === 'UNRESOLVED'
              ? '미해결 응답을 저장했어요. 같은 문제의 처리를 이어가요.'
              : '확인 상태를 다시 조회해 주세요.'}
        </p>
      )}
      {command.error && <p role="alert">{command.error}</p>}
      {command.notice && <p role="status">{command.notice}</p>}
      {command.remaining > 0 && (
        <p role="status">{command.remaining}초 후 다시 요청할 수 있어요.</p>
      )}
    </section>
  );
}
