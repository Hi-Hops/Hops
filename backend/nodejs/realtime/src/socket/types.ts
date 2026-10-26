import type { Namespace, Socket } from 'socket.io';
import type {
  Authentication,
  AuthFailureCode,
  ClientEvents,
  PublicEvents,
  SpringIncoming,
  SpringOutgoing,
  InterServerEvents,
  SocketData,
  SpringClient,
} from '../contracts/types.js';

export type ClientSocket = Socket<ClientEvents, PublicEvents, InterServerEvents, SocketData>;

export type ClientNamespace = Namespace<ClientEvents, PublicEvents, InterServerEvents, SocketData>;

export type ServiceSocket = Socket<SpringIncoming, SpringOutgoing, InterServerEvents>;

export type ServiceNamespace = Namespace<SpringIncoming, SpringOutgoing, InterServerEvents>;

// 서버마다 하나만 생성해 모든 처리자가 같은 Spring 연결과 연결 세대를 참조한다.
export interface RelayState {
  socket: ServiceSocket | null;
  epoch: number;
}

export interface SocketContext {
  clients: ClientNamespace;
  services: ServiceNamespace;
  relay: RelayState;
  spring: SpringClient;
  auth: {
    authenticate(socket: ClientSocket): Promise<Authentication>;
    revoke(socket: ClientSocket, code: AuthFailureCode): void;
  };
  ackTimeout: number;
}
