import type { ReactNode } from 'react';
import { Button } from '@/shared/ui/Button';
import { FormField } from '@/shared/ui/FormField';
import styles from './Cases.module.css';

export function CaseDraftHeader({
  badge,
  onFill,
}: {
  badge?: ReactNode;
  onFill?: (() => void) | undefined;
}) {
  return (
    <div className={styles.editorHeader}>
      <div className={styles.editorTitle}>
        <h3>해결 사례</h3>
        {badge}
      </div>
      <Button variant="secondary" disabled={!onFill} onClick={onFill}>
        AI 초안으로 채우기
      </Button>
    </div>
  );
}

type CaseDraftFieldsProps = {
  values?: Record<string, string>;
  disabled: boolean;
  onChange?: (name: string, value: string) => void;
};

export function CaseDraftFields({ values = {}, disabled, onChange }: CaseDraftFieldsProps) {
  return (
    <fieldset className={styles.draftFields} disabled={disabled}>
      <FormField id="case-title" label="사례 제목">
        <input
          id="case-title"
          value={values.title ?? ''}
          onChange={(e) => {
            onChange?.('title', e.target.value);
          }}
        />
      </FormField>
      <FormField id="case-problem" label="문제 설명">
        <textarea
          id="case-problem"
          value={values.problemStatement ?? ''}
          onChange={(e) => {
            onChange?.('problemStatement', e.target.value);
          }}
        />
      </FormField>
      <FormField id="case-action" label="조치 내용">
        <textarea
          id="case-action"
          value={values.action ?? ''}
          onChange={(e) => {
            onChange?.('action', e.target.value);
          }}
        />
      </FormField>
      <FormField id="case-verification" label="동작 확인 결과">
        <textarea
          id="case-verification"
          value={values.verification ?? ''}
          onChange={(e) => {
            onChange?.('verification', e.target.value);
          }}
        />
      </FormField>
      <FormField id="case-applicability" label="적용 조건">
        <textarea
          id="case-applicability"
          value={values.applicability ?? ''}
          onChange={(e) => {
            onChange?.('applicability', e.target.value);
          }}
        />
      </FormField>
      <FormField id="case-limitations" label="한계 (한 줄에 하나)">
        <textarea
          id="case-limitations"
          value={values.limitations ?? ''}
          onChange={(e) => {
            onChange?.('limitations', e.target.value);
          }}
        />
      </FormField>
      <FormField id="case-system-version" label="적용 시스템 버전 (선택)">
        <input
          id="case-system-version"
          value={values.appliesToSystemVersion ?? ''}
          placeholder="미확인 · 모르면 비워 두세요"
          onChange={(e) => {
            onChange?.('appliesToSystemVersion', e.target.value);
          }}
        />
      </FormField>
      <FormField id="case-scope" label="공개 범위">
        <select
          id="case-scope"
          value={values.accessScope ?? 'SYSTEM_STAFF'}
          onChange={(e) => {
            onChange?.('accessScope', e.target.value);
          }}
        >
          <option value="SYSTEM_STAFF">담당자 내부용</option>
          <option value="EMPLOYEE">직원용</option>
        </select>
      </FormField>
    </fieldset>
  );
}
