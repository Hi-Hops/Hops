import { Dialog } from 'radix-ui';
import type { ReactNode } from 'react';
import { Button } from './Button';
import styles from './RouteModal.module.css';
type RouteModalProps = {
  title: string;
  description: string;
  onClose: () => void;
  returnFocusId?: string;
  children: ReactNode;
};
export function RouteModal({
  title,
  description,
  onClose,
  returnFocusId,
  children,
}: RouteModalProps) {
  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content
          className={styles.content}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            const target = returnFocusId ? document.getElementById(returnFocusId) : null;
            (target ?? document.getElementById('page-title'))?.focus();
          }}
        >
          <header className={styles.header}>
            <div>
              <Dialog.Title>{title}</Dialog.Title>
              <Dialog.Description>{description}</Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <Button variant="ghost" aria-label="상세 닫기">
                ✕
              </Button>
            </Dialog.Close>
          </header>
          <div className={styles.body}>{children}</div>
          <footer className={styles.footer}>
            <span>조회 전용 · 변경 작업은 후속 기능이에요</span>
            <Dialog.Close asChild>
              <Button variant="secondary">닫기</Button>
            </Dialog.Close>
          </footer>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
