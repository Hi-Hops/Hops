import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { SystemCommandButton, useSystemOperations } from '@/features/inquiry';
import type { ImageAttachmentMetadata, InquiryResult } from '@/shared/api/types/hopsSystem1';
import { Button } from '@/shared/ui/Button';
import { getImageMetadata, imageProblemLinkCommand } from '../api/systemChatApi';
import styles from './ImageProblemLinkEditor.module.css';

type Props = {
  inquiry: InquiryResult;
  messageId: string;
  attachmentId: string;
  disabled: boolean;
};

export function ImageProblemLinkEditor({ inquiry, messageId, attachmentId, disabled }: Props) {
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
        {open ? '공개 대상 닫기' : '공개 문제 선택'}
      </Button>
      {open && metadata.isPending && <p role="status">현재 공개 대상을 확인하고 있어요.</p>}
      {open && metadata.isError && (
        <div role="alert">
          <p>이미지 공개 대상을 불러오지 못했어요.</p>
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
      {open && metadata.isSuccess && (
        <LinkForm
          key={`${attachmentId}:${metadata.data.linkRevision}`}
          inquiry={inquiry}
          metadata={metadata.data}
          disabled={disabled || metadata.isFetching}
          actor={actor}
        />
      )}
    </div>
  );
}

function LinkForm({
  inquiry,
  metadata,
  disabled,
  actor,
}: {
  inquiry: InquiryResult;
  metadata: ImageAttachmentMetadata;
  disabled: boolean;
  actor: string;
}) {
  const operationKey = `image-problem-links:${inquiry.id}:${metadata.id}`;
  const pending = useSystemOperations((state) => state.pending[`${actor}:${operationKey}`]);
  const [selected, setSelected] = useState(() => new Set(metadata.problemIds));
  const [confirmed, setConfirmed] = useState(false);
  const known = new Set(inquiry.problems.map((problem) => problem.id));
  const missing = metadata.problemIds.some((id) => !known.has(id));
  const changed =
    selected.size !== metadata.problemIds.length ||
    metadata.problemIds.some((id) => !selected.has(id));
  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    setConfirmed(false);
  }
  return (
    <div className={styles.form}>
      <p>
        선택한 문제의 현재 담당자에게 이미지 전체가 보여요. 민감한 부분이 가려졌는지 확인해 주세요.
      </p>
      {missing && <p role="alert">연결된 문제를 확인할 수 없어요. 문의를 다시 불러와 주세요.</p>}
      {!inquiry.problems.length && <p>아직 연결할 문제가 없어요.</p>}
      <fieldset disabled={disabled || Boolean(pending) || missing} className={styles.options}>
        <legend>이 이미지를 공유할 문제</legend>
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
        {selected.size > 0 && (
          <label className={styles.confirm}>
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(event) => {
                setConfirmed(event.target.checked);
              }}
            />{' '}
            선택한 문제의 담당자에게 이미지 전체가 보이는 것을 확인했어요.
          </label>
        )}
      </fieldset>
      <p className={styles.hint}>모두 선택 해제하면 담당자 공개가 중지돼요.</p>
      <SystemCommandButton
        operationKey={operationKey}
        createCommand={(requestId) =>
          imageProblemLinkCommand(inquiry.id, metadata.id, {
            requestId,
            expectedInquiryVersion: inquiry.inquiryVersion,
            expectedLinkRevision: metadata.linkRevision,
            problemIds: [...selected],
            confirmImageDisclosure: selected.size > 0 && confirmed,
          })
        }
        label="공개 대상 저장"
        disabled={disabled || missing || !changed || (selected.size > 0 && !confirmed)}
      />
    </div>
  );
}
