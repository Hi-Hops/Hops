import type { ComponentProps } from 'react';
import styles from './Controls.module.css';

type ButtonProps = ComponentProps<'button'> & {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  isLoading?: boolean;
};

export function Button({
  variant = 'primary',
  isLoading = false,
  disabled = false,
  className,
  children,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      {...rest}
      type={type}
      disabled={disabled || isLoading}
      aria-busy={isLoading}
      className={`${styles.button} ${styles[variant]} ${className ?? ''}`}
    >
      {children}
    </button>
  );
}
