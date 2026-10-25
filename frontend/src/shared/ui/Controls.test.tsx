import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Button } from './Button';
import { TextField } from './TextField';

describe('공통 입력·버튼', () => {
  it('로딩 중에는 버튼의 이름을 유지하고 중복 조작을 막는다', () => {
    render(<Button isLoading>로그인</Button>);
    expect(screen.getByRole('button', { name: '로그인' })).toBeDisabled();
  });
  it('입력 라벨과 오류 안내를 연결한다', () => {
    render(<TextField label="아이디" error="아이디를 확인해 주세요." />);
    expect(screen.getByLabelText('아이디')).toHaveAccessibleDescription('아이디를 확인해 주세요.');
    expect(screen.getByRole('alert')).toBeVisible();
  });
});
