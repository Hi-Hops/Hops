import { useState } from 'react';
import { SystemCommandButton, useSystemOperations } from '@/features/inquiry';
import { useSession } from '@/features/auth';
import type {
  ImageObservationDecision,
  ImageObservationQuestion,
} from '@/shared/api/types/hopsSystem1';
import { imageObservationResponseCommand } from '../api/systemChatApi';
import styles from './ImageObservationConfirmation.module.css';

const decisions: { value: ImageObservationDecision; label: string }[] = [
  { value: 'CONFIRMED', label: '맞아요' },
  { value: 'INCORRECT', label: '아니에요' },
  { value: 'UNKNOWN', label: '잘 모르겠어요' },
];
const stateLabels: Record<ImageObservationQuestion['state'], string> = {
  UNCONFIRMED: '아직 확인하지 않았어요',
  CONFIRMED: '맞다고 확인했어요',
  INCORRECT: '아니라고 확인했어요',
  UNKNOWN: '잘 모르겠다고 응답했어요',
};

export function ImageObservationConfirmation({
  inquiryId,
  inquiryVersion,
  observation,
  disabled,
}: {
  inquiryId: string;
  inquiryVersion: number;
  observation: ImageObservationQuestion;
  disabled: boolean;
}) {
  const actorId = useSession((state) => state.user?.id) ?? '';
  const operationKey = `image-observation:${inquiryId}:${observation.observationId}`;
  const pending = useSystemOperations((state) => state.pending[`${actorId}:${operationKey}`]);
  const [decision, setDecision] = useState<ImageObservationDecision | null>(null);
  return (
    <section className={styles.confirmation} aria-label="이미지 관찰 확인">
      <p>이미지에서 이렇게 보였어요: {observation.text}</p>
      <p className={styles.state}>{stateLabels[observation.state]}</p>
      <fieldset disabled={disabled || Boolean(pending)} className={styles.options}>
        <legend>이 내용이 맞나요?</legend>
        {decisions.map((option) => (
          <label key={option.value}>
            <input
              type="radio"
              name={`image-observation-${observation.observationId}`}
              value={option.value}
              checked={decision === option.value}
              onChange={() => {
                setDecision(option.value);
              }}
            />{' '}
            {option.label}
          </label>
        ))}
      </fieldset>
      <SystemCommandButton
        operationKey={operationKey}
        createCommand={(requestId) => {
          if (decision === null) throw new Error('확인 응답을 선택해 주세요.');
          return imageObservationResponseCommand(inquiryId, {
            requestId,
            expectedInquiryVersion: inquiryVersion,
            responses: [
              {
                observationId: observation.observationId,
                expectedObservationRevision: observation.observationRevision,
                decision,
              },
            ],
          });
        }}
        label="응답 저장"
        disabled={disabled || decision === null}
      />
    </section>
  );
}
