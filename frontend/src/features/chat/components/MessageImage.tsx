import { useQuery } from '@tanstack/react-query';
import { useCallback } from 'react';
import { getMessageImage } from '../api/systemChatApi';
import styles from './MessageImage.module.css';

type MessageImageProps = {
  actor: string;
  inquiryId: string;
  attachmentId: string;
  position: number;
};

export function MessageImage({ actor, inquiryId, attachmentId, position }: MessageImageProps) {
  const image = useQuery({
    queryKey: ['hops-system-1', 'chat-image', actor, inquiryId, attachmentId],
    queryFn: ({ signal }) => getMessageImage(inquiryId, attachmentId, signal),
    enabled: Boolean(actor),
    retry: false,
    gcTime: 0,
    staleTime: 0,
  });
  const attach = useCallback(
    (node: HTMLImageElement | null) => {
      if (!node || !image.data) return;
      const url = URL.createObjectURL(image.data);
      node.src = url;
      return () => {
        node.removeAttribute('src');
        URL.revokeObjectURL(url);
      };
    },
    [image.data],
  );
  if (image.isError) return <p role="status">첨부 이미지를 불러오지 못했어요.</p>;
  if (!image.data) return <p role="status">첨부 이미지를 불러오는 중이에요.</p>;
  return <img className={styles.image} ref={attach} alt={`첨부 이미지 ${String(position + 1)}`} />;
}
