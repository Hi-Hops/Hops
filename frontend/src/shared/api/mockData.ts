import data from '@fixtures/fixtures.json';
import type { ContractTypes } from './types';

/** 공용 JSON은 specs/contracts/validate.py로 검사한다. 외부 JSON 경계의 타입 변환이다. */
export function fixture<K extends keyof ContractTypes>(id: string, type: K): ContractTypes[K] {
  const found = data.cases.find((row) => row.id === id);
  if (!found) throw new Error(`공용 Mock 데이터가 없어요: ${id}`);
  if (found.type !== type) throw new Error(`공용 Mock 타입이 달라요: ${id}`);
  return structuredClone(found.value) as ContractTypes[K];
}
