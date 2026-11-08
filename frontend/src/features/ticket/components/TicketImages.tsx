import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { Button } from '@/shared/ui/Button';
import { getTicketImage, getTicketImages } from '../api/systemTicketApi';
import styles from './TicketImages.module.css';

export function TicketImages({ ticketId }: { ticketId: string }) {
  const actor = useSession((state) => state.user?.id) ?? '';
  const images = useQuery({
    queryKey: ['hops-system-1', 'tickets', 'images', actor, ticketId],
    queryFn: ({ signal }) => getTicketImages(ticketId, signal),
    enabled: Boolean(actor),
    refetchInterval: 5000,
    retry: false,
    gcTime: 0,
    staleTime: 0,
  });
  if (images.isError)
    return (
      <section className={styles.images} aria-label="문의자가 공개한 이미지">
        <p role="alert">이미지 공개 상태를 확인하지 못했어요.</p>
        <Button
          variant="secondary"
          onClick={() => {
            void images.refetch();
          }}
        >
          다시 확인
        </Button>
      </section>
    );
  return (
    <section className={styles.images} aria-label="문의자가 공개한 이미지">
      <h3>문의자가 공개한 이미지</h3>
      {images.isFetching && <p role="status">현재 공개 권한을 확인하고 있어요.</p>}
      {!images.isFetching && images.data?.length === 0 && <p>공개된 이미지가 없어요.</p>}
      <div hidden={images.isFetching}>
        {images.data?.map((image, index) => (
          <TicketImage
            key={image.id}
            actor={actor}
            ticketId={ticketId}
            imageId={image.id}
            position={index}
          />
        ))}
      </div>
    </section>
  );
}

function TicketImage({
  actor,
  ticketId,
  imageId,
  position,
}: {
  actor: string;
  ticketId: string;
  imageId: string;
  position: number;
}) {
  const image = useQuery({
    queryKey: ['hops-system-1', 'tickets', 'image-content', actor, ticketId, imageId],
    queryFn: ({ signal }) => getTicketImage(ticketId, imageId, signal),
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
  if (image.isError)
    return <p role="status">이미지를 볼 수 없어요. 공개 상태를 다시 확인해 주세요.</p>;
  if (!image.data || image.isFetching)
    return <p role="status">이미지 접근 권한을 확인하고 있어요.</p>;
  return (
    <img
      className={styles.image}
      ref={attach}
      alt={`문의자가 공개한 이미지 ${String(position + 1)}`}
    />
  );
}
