import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it } from 'vitest';
import { COLLAPSE_AT, ProgressSteps } from './ProgressSteps';

const steps = Array.from({ length: COLLAPSE_AT + 1 }, (_, i) => ({
  stepNo: i + 1,
  text: `단계 ${String(i + 1)}`,
}));

it('기준 개수 이상 쌓인 진행 단계는 자동으로 접어 최신 단계만 보여주고 아이콘으로 펼친다', async () => {
  render(<ProgressSteps steps={steps} />);
  expect(screen.getAllByRole('listitem')).toHaveLength(1);
  expect(screen.getByText(`단계 ${String(steps.length)}`)).toBeVisible();

  await userEvent.click(
    screen.getByRole('button', { name: `이전 진행 단계 ${String(steps.length - 1)}개 펼치기` }),
  );
  expect(screen.getAllByRole('listitem')).toHaveLength(steps.length);
  const collapse = screen.getByRole('button', { name: '진행 단계 접기' });
  expect(collapse).toHaveAttribute('aria-expanded', 'true');
  // 펼친 뒤에는 접기 버튼이 마지막 단계가 아닌 맨 위 단계 옆에 있다.
  expect(screen.getAllByRole('listitem')[0]).toContainElement(collapse);
});

it('기준 개수 미만에서는 모든 단계를 보여주고 접기 아이콘을 표시하지 않는다', () => {
  render(<ProgressSteps steps={steps.slice(0, COLLAPSE_AT - 1)} />);
  expect(screen.getAllByRole('listitem')).toHaveLength(COLLAPSE_AT - 1);
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
});

it('완료 기록으로 남긴 진행 단계는 진행 중 표시 없이 모든 단계를 체크로 보여준다', () => {
  render(<ProgressSteps steps={steps.slice(0, COLLAPSE_AT - 1)} done />);
  expect(screen.getByRole('list', { name: '완료된 진행 단계' })).toBeVisible();
  for (const item of screen.getAllByRole('listitem'))
    expect(item).not.toHaveAttribute('data-current');
});
