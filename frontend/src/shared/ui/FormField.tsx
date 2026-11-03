import type { ReactNode } from 'react';
import styles from './FormField.module.css';

type FormFieldProps = {
  id: string;
  label: string;
  children: ReactNode;
  hint?: string;
};
export function FormField({ id, label, children, hint }: FormFieldProps) {
  return (
    <div className={styles.field}>
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && <p>{hint}</p>}
    </div>
  );
}
