import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import type { CurrentUser } from '@/shared/api/types';
import { ApiError } from '@/shared/api/client';
import { Button } from '@/shared/ui/Button';
import { TextField } from '@/shared/ui/TextField';
import { login } from '../api/authApi';
import styles from './LoginForm.module.css';

export function LoginForm({
  onSuccess,
  expired = false,
}: {
  onSuccess: (user: CurrentUser) => void;
  expired?: boolean;
}) {
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const mutation = useMutation({
    mutationFn: login,
    onSuccess: (user) => {
      setPassword('');
      onSuccess(user);
    },
    onError: () => {
      setPassword('');
    },
  });
  return (
    <section className={styles.card} aria-labelledby="login-title">
      <div className={styles.brand}>
        <span>H</span>Hops
      </div>
      <div className={styles.heading}>
        <h1 id="login-title">Hops에 로그인</h1>
        <p>사내 계정으로 로그인하세요.</p>
      </div>
      {expired && (
        <div className={styles.notice} role="status">
          <strong>다시 로그인해 주세요</strong>
          <p>
            이미 접수된 작업은 계속 진행돼요. 같은 계정으로 로그인하면 작성 중인 내용을 이어갈 수
            있어요.
          </p>
        </div>
      )}
      <form
        className={styles.form}
        onSubmit={(event) => {
          event.preventDefault();
          if (!mutation.isPending) mutation.mutate({ loginId, password });
        }}
      >
        <TextField
          label="아이디"
          name="username"
          autoComplete="username"
          placeholder="아이디를 입력하세요"
          value={loginId}
          onChange={(event) => {
            setLoginId(event.target.value);
            mutation.reset();
          }}
          required
        />
        <TextField
          label="비밀번호"
          name="password"
          autoComplete="current-password"
          type="password"
          placeholder="비밀번호를 입력하세요"
          value={password}
          onChange={(event) => {
            setPassword(event.target.value);
          }}
          required
        />
        {mutation.isError && (
          <p className={styles.error} role="alert">
            {mutation.error instanceof ApiError
              ? mutation.error.body.message
              : '로그인하지 못했어요. 다시 시도해 주세요.'}
          </p>
        )}
        <Button type="submit" isLoading={mutation.isPending} disabled={!loginId || !password}>
          {mutation.isPending ? '로그인 중' : '로그인'}
        </Button>
      </form>
      <p className={styles.caption}>사내 IT 문의를 시작하고 처리 현황을 확인하세요.</p>
      <details className={styles.demo}>
        <summary>Mock 테스트 계정 안내</summary>
        <p>
          문의자: demo.requester
          <br />
          네트워크: demo.network
          <br />
          backend: demo.backend
          <br />
          업무 관리자: demo.admin
          <br />
          공통 비밀번호: mock-only-password
        </p>
      </details>
    </section>
  );
}
