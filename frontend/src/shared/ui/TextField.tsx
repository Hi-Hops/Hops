import { useId } from 'react';
import type { ComponentProps } from 'react';
import styles from './Controls.module.css';

type TextFieldProps = ComponentProps<'input'> & { label: string; error?: string; hint?: string };

export function TextField({ label, error, hint, id, ...rest }: TextFieldProps) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const detail = error ?? hint;
  return (
    <div className={styles.field}>
      <label htmlFor={inputId}>{label}</label>
      <input
        {...rest}
        id={inputId}
        aria-invalid={Boolean(error)}
        aria-describedby={detail ? `${inputId}-detail` : undefined}
      />
      {detail && (
        <small id={`${inputId}-detail`} role={error ? 'alert' : undefined}>
          {detail}
        </small>
      )}
    </div>
  );
}
