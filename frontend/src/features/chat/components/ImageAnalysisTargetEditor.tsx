import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { SystemCommandButton, useSystemOperations } from '@/features/inquiry';
import type { InquiryResult } from '@/shared/api/types/hopsSystem1';
import { Button } from '@/shared/ui/Button';
import { getImageMetadata, imageAnalysisTargetCommand } from '../api/systemChatApi';
import styles from './ImageProblemLinkEditor.module.css';

type Props = {
  inquiry: InquiryResult;
  messageId: string;
  attachmentId: string;
  disabled: boolean;
};

export function ImageAnalysisTargetEditor({ inquiry, messageId, attachmentId, disabled }: Props) {
  const actor = useSession((state) => state.user?.id) ?? '';
  const [open, setOpen] = useState(false);
  const metadata = useQuery({
    queryKey: ['hops-system-1', 'chat', actor, inquiry.id, 'image-links', attachmentId],
    queryFn: ({ signal }) => getImageMetadata(attachmentId, messageId, signal),
    enabled: open && Boolean(actor),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  return (
    <div className={styles.editor}>
      <Button
        variant="ghost"
        onClick={() => {
          setOpen(!open);
        }}
      >
        {open ? '분석 대상 닫기' : '분석 문제 선택'}
      </Button>
      {open && metadata.isPending && <p role="status">현재 분석 대상을 확인하고 있어요.</p>}
      {open && metadata.isError && (
        <div role="alert">
          <p>이미지 분석 대상을 불러오지 못했어요.</p>
          <Button
            variant="secondary"
            onClick={() => {
              void metadata.refetch();
            }}
          >
            다시 확인
          </Button>
        </div>
      )}
      {open &&
        metadata.isSuccess &&
        (metadata.data.analysisRevision === undefined ||
        metadata.data.analysisProblemIds === undefined ? (
          <p role="alert">이미지 분석 대상 선택을 사용할 수 없어요.</p>
        ) : (
          <TargetForm
            key={`${attachmentId}:${metadata.data.analysisRevision}`}
            inquiry={inquiry}
            attachmentId={attachmentId}
            revision={metadata.data.analysisRevision}
            problemIds={metadata.data.analysisProblemIds}
            disabled={disabled || metadata.isFetching}
            actor={actor}
          />
        ))}
    </div>
  );
}

function TargetForm({
  inquiry,
  attachmentId,
  revision,
  problemIds,
  disabled,
  actor,
}: {
  inquiry: InquiryResult;
  attachmentId: string;
  revision: number;
  problemIds: string[];
  disabled: boolean;
  actor: string;
}) {
  const operationKey = `image-analysis-targets:${inquiry.id}:${attachmentId}`;
  const pending = useSystemOperations((state) => state.pending[`${actor}:${operationKey}`]);
  const [selected, setSelected] = useState(() => new Set(problemIds));
  const known = new Set(inquiry.problems.map((problem) => problem.id));
  const missing = problemIds.some((id) => !known.has(id));
  const changed = selected.size !== problemIds.length || problemIds.some((id) => !selected.has(id));
  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }
  return (
    <div className={styles.form}>
      <p>
        이 이미지로 분석할 문제를 선택해 주세요. 선택만으로 분석이 시작되거나 담당자에게 공개되지
        않아요.
      </p>
      {missing && <p role="alert">선택된 문제를 확인할 수 없어요. 문의를 다시 불러와 주세요.</p>}
      {!inquiry.problems.length && <p>아직 선택할 문제가 없어요.</p>}
      <fieldset disabled={disabled || Boolean(pending) || missing} className={styles.options}>
        <legend>이 이미지를 분석할 문제</legend>
        {inquiry.problems.map((problem) => (
          <label key={problem.id}>
            <input
              type="checkbox"
              checked={selected.has(problem.id)}
              onChange={() => {
                toggle(problem.id);
              }}
            />{' '}
            {problem.summary}
          </label>
        ))}
      </fieldset>
      <p className={styles.hint}>모두 선택 해제하면 이 이미지에 지정한 분석 대상이 사라져요.</p>
      <SystemCommandButton
        operationKey={operationKey}
        createCommand={(requestId) =>
          imageAnalysisTargetCommand(inquiry.id, attachmentId, {
            requestId,
            expectedInquiryVersion: inquiry.inquiryVersion,
            expectedAnalysisRevision: revision,
            problemIds: [...selected],
          })
        }
        label="분석 대상 저장"
        disabled={disabled || missing || !changed || selected.size > 100}
      />
    </div>
  );
}
