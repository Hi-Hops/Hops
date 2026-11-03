import { apiRequest, ApiError } from './client';
import { readReceipt } from './systemWire';
import { localError } from './wire';
import type { CommandReceipt, ResourceRef } from './types/hopsSystem1';

export type PendingSystemCommand = {
  path: string;
  body: { requestId: string } & Record<string, unknown>;
  expectedResource?: { type: string; id?: string };
};
export type SystemCommand = PendingSystemCommand & {
  expectedResource: { type: string; id?: string };
};

/** 원 명령의 키·경로·본문을 유지한다. GET 실패나 404를 미저장 증거로 사용하지 않는다. */
export async function sendSystemCommand(
  command: PendingSystemCommand,
  confirm = false,
): Promise<CommandReceipt> {
  let value: unknown;
  if (confirm) {
    try {
      value = await apiRequest<unknown>(
        `/api/v2/operations/by-request/${encodeURIComponent(command.body.requestId)}`,
      );
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 404) throw error;
      // 사용자가 선택한 같은 요청 재확인이다. 새 키나 변경된 본문을 만들지 않는다.
    }
  }
  if (value === undefined)
    value = await apiRequest<unknown>(command.path, {
      method: 'POST',
      body: command.body,
      idempotencyKey: command.body.requestId,
    });
  let receipt: CommandReceipt;
  try {
    receipt = readReceipt(value);
    if (receipt.requestId !== command.body.requestId) throw new Error('요청 ID 불일치');
    if (
      receipt.state === 'SUCCEEDED' &&
      command.expectedResource &&
      (receipt.resource?.type !== command.expectedResource.type ||
        (command.expectedResource.id !== undefined &&
          receipt.resource.id !== command.expectedResource.id))
    )
      throw new Error('명령 결과 대상 불일치');
  } catch {
    throw new ApiError(
      0,
      localError(
        'INVALID_RESPONSE',
        '저장 결과를 확인하지 못했어요. 같은 요청으로 다시 확인해 주세요.',
      ),
    );
  }
  return receipt;
}

export function savedResource(receipt: CommandReceipt, type: string): ResourceRef {
  if (receipt.state === 'REJECTED' && receipt.error) throw new ApiError(409, receipt.error);
  if (receipt.state !== 'SUCCEEDED' || receipt.resource?.type !== type)
    throw new ApiError(
      0,
      localError(
        'OPERATION_UNCONFIRMED',
        '저장 결과 확인 중이에요. 같은 요청으로 다시 확인해 주세요.',
      ),
    );
  return receipt.resource;
}

export async function systemGet<T>(
  path: string,
  read: (value: unknown) => T,
  signal?: AbortSignal,
): Promise<T> {
  const value = await apiRequest<unknown>(`/api/v2${path}`, { signal });
  try {
    return read(value);
  } catch {
    throw new ApiError(
      0,
      localError('INVALID_RESPONSE', '서버 응답 형식이 계약과 달라요. 다시 조회해 주세요.'),
    );
  }
}
