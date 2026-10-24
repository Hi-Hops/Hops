/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_DATA_MODE?: string;
  readonly VITE_SOCKET_PATH?: string;
  readonly VITE_MOCK_REQUESTER_ID?: string;
  readonly VITE_MOCK_NETWORK_ID?: string;
  readonly VITE_MOCK_BACKEND_ID?: string;
  readonly VITE_MOCK_ADMIN_ID?: string;
  readonly VITE_MOCK_PASSWORD?: string;
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_SOCKET_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
