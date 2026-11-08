import { SystemCommandButton } from '@/features/inquiry';
import { imageAnalysisRunCommand } from '../api/systemChatApi';
import styles from './ImageProblemLinkEditor.module.css';

type Props = {
  available: boolean;
  operationKey: string;
  inquiryId: string;
  inquiryVersion: number;
  attachmentId: string;
  revision: number;
  disabled: boolean;
};

export function ImageAnalysisRunControl({
  available,
  operationKey,
  inquiryId,
  inquiryVersion,
  attachmentId,
  revision,
  disabled,
}: Props) {
  if (!available) return <p className={styles.hint}>이미지 재분석은 아직 사용할 수 없어요.</p>;
  return (
    <SystemCommandButton
      operationKey={operationKey}
      createCommand={(requestId) =>
        imageAnalysisRunCommand(inquiryId, attachmentId, {
          requestId,
          expectedInquiryVersion: inquiryVersion,
          expectedAnalysisRevision: revision,
        })
      }
      label="이미지 재분석 요청"
      disabled={disabled}
    />
  );
}
