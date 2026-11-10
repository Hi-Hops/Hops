import { Dialog } from 'radix-ui';
import type { ReactNode } from 'react';
import { Button } from './Button';
import styles from './RouteModal.module.css';
type RouteModalProps = {
  title: string;
  /** 제목을 줄여 표시할 때 마우스를 올리면 보여줄 전체 값. */
  titleHint?: string | undefined;
  description?: string;
  onClose: () => void;
  returnFocusId?: string;
  children: ReactNode;
  footer?: ReactNode;
  size?: 'wide' | 'compact' | 'narrow';
  padded?: boolean;
  fixedHeight?: boolean;
};
export function RouteModal({
  title,
  titleHint,
  description,
  onClose,
  returnFocusId,
  children,
  footer,
  size = 'wide',
  padded = true,
  fixedHeight = false,
}: RouteModalProps) {
  const contentClassName = [
    styles.content,
    size !== 'wide' && styles[size],
    fixedHeight && styles.fixedHeight,
  ]
    .filter(Boolean)
    .join(' ');
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
          className={contentClassName}
          {...(description ? {} : { 'aria-describedby': undefined })}
          onCloseAutoFocus={(event) => {
            if (returnFocusId) {
              event.preventDefault();
              const target = document.getElementById(returnFocusId);
              (target ?? document.getElementById('page-title'))?.focus();
            }
          }}
        >
          <header className={styles.header}>
            <div>
              <Dialog.Title title={titleHint}>{title}</Dialog.Title>
              {description && <Dialog.Description>{description}</Dialog.Description>}
            </div>
            <Dialog.Close asChild>
              <Button variant="ghost" aria-label="상세 닫기">
                ✕
              </Button>
            </Dialog.Close>
          </header>
          <div className={(styles.body ?? '') + (padded ? ' ' + (styles.padded ?? '') : '')}>
            {children}
          </div>
          {footer !== null && (
            <footer className={styles.footer}>
              {footer}
              <Dialog.Close asChild>
                <Button variant="secondary">닫기</Button>
              </Dialog.Close>
            </footer>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
