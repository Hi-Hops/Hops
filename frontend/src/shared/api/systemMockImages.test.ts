import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { SystemMockBackend } from './systemMockBackend';
import { systemMockBackend } from './systemMockBackend';
import { apiImageRequest, registerTokenProvider } from './client';
import { readImageMetadata, readLogin, readMessages, readReceipt } from './systemWire';

const pngFixture = btoa('\x89PNG\r\n\x1a\nsynthetic-only');
let backend: SystemMockBackend;
let owner: string;
let other: string;

beforeEach(() => {
  vi.useFakeTimers();
  backend = new SystemMockBackend();
  owner = readLogin(
    backend.request('/api/v2/auth/login', 'POST', TEST_LOGINS.requester, null),
  ).accessToken;
  other = readLogin(
    backend.request('/api/v2/auth/login', 'POST', TEST_LOGINS.network, null),
  ).accessToken;
});
afterEach(() => {
  backend.reset();
  vi.useRealTimers();
});

function post(path: string, data: Record<string, unknown>, token = owner) {
  const body = { ...data, requestId: crypto.randomUUID() };
  return readReceipt(backend.request('/api/v2' + path, 'POST', body, token, body.requestId));
}
function upload() {
  const receipt = post('/image-attachments', { mediaType: 'image/png', dataBase64: pngFixture });
  if (!receipt.resource) throw new Error('첨부 결과 필요');
  return receipt.resource.id;
}
function create(attachmentIds: string[]) {
  const receipt = post('/inquiries', {
    content: '가상 오류',
    reportedSystemId: null,
    attachmentIds,
  });
  if (!receipt.resource) throw new Error('문의 결과 필요');
  return receipt.resource.id;
}

describe('F003 Mock 가림 이미지 흐름', () => {
  it('두 이미지의 업로드와 문의 연결·본인 이력 조회를 지원하고 이미지 분석을 주장하지 않는다', async () => {
    const images = [upload(), upload()];
    const inquiryId = create(images);
    const read = () =>
      readMessages(
        backend.request(`/api/v2/inquiries/${inquiryId}/messages`, 'GET', undefined, owner),
      ).items;
    const attached = read()[0];
    expect(attached?.attachments?.map((item) => item.id)).toEqual(images);
    const imageId = images[0];
    if (!attached || !imageId) throw new Error('첨부 메시지 필요');
    expect(
      readImageMetadata(
        backend.request(`/api/v2/image-attachments/${imageId}`, 'GET', undefined, owner),
      ),
    ).toMatchObject({ state: 'BOUND', messageId: attached.id });
    const blob = backend.request(
      `/api/v2/inquiries/${inquiryId}/image-attachments/${imageId}/content`,
      'GET',
      undefined,
      owner,
    );
    expect(blob).toBeInstanceOf(Blob);
    expect(blob).toMatchObject({ type: 'image/png', size: atob(pngFixture).length });
    await vi.advanceTimersByTimeAsync(200);
    expect(read().at(-1)?.content).toContain('시연 환경에서는 첨부 이미지를 분석하지 않아요.');
  });

  it('다른 사용자·다른 문의의 이미지 조회와 다른 사용자 첨부 결합을 거부한다', () => {
    const imageId = upload();
    const another = post(
      '/image-attachments',
      { mediaType: 'image/png', dataBase64: pngFixture },
      other,
    ).resource?.id;
    if (!another) throw new Error('다른 사용자 첨부 필요');
    expect(() => create([imageId, another])).toThrow(expect.objectContaining({ status: 404 }));
    const inquiryId = create([imageId]);
    expect(() =>
      backend.request(
        `/api/v2/inquiries/${inquiryId}/image-attachments/${imageId}/content`,
        'GET',
        undefined,
        other,
      ),
    ).toThrow();
    expect(() =>
      backend.request(
        `/api/v2/inquiries/${crypto.randomUUID()}/image-attachments/${imageId}/content`,
        'GET',
        undefined,
        owner,
      ),
    ).toThrow();
    expect(() =>
      backend.request(`/api/v2/image-attachments/${imageId}`, 'GET', undefined, other),
    ).toThrow(expect.objectContaining({ status: 404 }));
  });

  it('중복·재사용·PNG 형식 오류를 거부하고 같은 업로드 키는 같은 결과를 반환한다', () => {
    const requestId = crypto.randomUUID();
    const body = { requestId, mediaType: 'image/png', dataBase64: pngFixture };
    const first = readReceipt(
      backend.request('/api/v2/image-attachments', 'POST', body, owner, requestId),
    );
    expect(
      readReceipt(backend.request('/api/v2/image-attachments', 'POST', body, owner, requestId)),
    ).toEqual(first);
    expect(() =>
      backend.request(
        '/api/v2/image-attachments',
        'POST',
        { ...body, dataBase64: btoa('other') },
        owner,
        requestId,
      ),
    ).toThrow(expect.objectContaining({ status: 409 }));
    const imageId = first.resource?.id;
    if (!imageId) throw new Error('첨부 필요');
    expect(() => create([imageId, imageId])).toThrow(expect.objectContaining({ status: 400 }));
    create([imageId]);
    expect(() => create([imageId])).toThrow(expect.objectContaining({ status: 409 }));
    expect(() =>
      post('/image-attachments', { mediaType: 'image/png', dataBase64: btoa('invalid') }),
    ).toThrow(expect.objectContaining({ status: 400 }));
  });

  it('구현되지 않은 이미지 공개 명령은 문의 메시지로 처리하지 않는다', () => {
    const imageId = upload();
    const inquiryId = create([imageId]);
    expect(() =>
      post(`/inquiries/${inquiryId}/image-attachments/${imageId}/problem-links`, {
        expectedInquiryVersion: 1,
        content: '전송되면 안 되는 값',
        problemIds: [],
      }),
    ).toThrow(expect.objectContaining({ status: 404 }));
    const items = readMessages(
      backend.request(`/api/v2/inquiries/${inquiryId}/messages`, 'GET', undefined, owner),
    ).items;
    expect(items).toHaveLength(1);
  });

  it('새로고침에 해당하는 Mock 초기화 후 이미지를 보존하지 않는다', () => {
    const id = upload();
    backend.reset();
    const fresh = readLogin(
      backend.request('/api/v2/auth/login', 'POST', TEST_LOGINS.requester, null),
    ).accessToken;
    expect(() =>
      backend.request(`/api/v2/image-attachments/${id}`, 'GET', undefined, fresh),
    ).toThrow(expect.objectContaining({ status: 404 }));
  });
});

it('F003-CONTRACT Mock 이미지도 인증된 클라이언트 경계에서 Blob으로 조회한다', async () => {
  vi.useRealTimers();
  systemMockBackend.reset();
  const token = readLogin(
    systemMockBackend.request('/api/v2/auth/login', 'POST', TEST_LOGINS.requester, null),
  ).accessToken;
  registerTokenProvider(() => token);
  try {
    const uploadId = crypto.randomUUID();
    const uploadReceipt = readReceipt(
      systemMockBackend.request(
        '/api/v2/image-attachments',
        'POST',
        { requestId: uploadId, mediaType: 'image/png', dataBase64: pngFixture },
        token,
        uploadId,
      ),
    );
    const imageId = uploadReceipt.resource?.id;
    if (!imageId) throw new Error('첨부 필요');
    const createId = crypto.randomUUID();
    const inquiryReceipt = readReceipt(
      systemMockBackend.request(
        '/api/v2/inquiries',
        'POST',
        {
          requestId: createId,
          content: '가상 오류',
          reportedSystemId: null,
          attachmentIds: [imageId],
        },
        token,
        createId,
      ),
    );
    const inquiryId = inquiryReceipt.resource?.id;
    if (!inquiryId) throw new Error('문의 필요');
    const blob = await apiImageRequest(
      `/api/v2/inquiries/${inquiryId}/image-attachments/${imageId}/content`,
    );
    expect(blob).toMatchObject({ type: 'image/png', size: atob(pngFixture).length });
  } finally {
    registerTokenProvider(() => null);
    systemMockBackend.reset();
  }
});
