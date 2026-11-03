// docs/DATA.md의 hops-demo-v1 고정 가상 카탈로그.
import type { CurrentUser, SupportedSystem } from './types/hopsSystem1';
export const department = {
  id: '2e3f4c5d-5c26-5026-94bd-1efac7067ff1',
  code: 'DEMO_STAFF',
  name: '가상 IT서비스팀',
};
export const systems: [SupportedSystem, SupportedSystem, SupportedSystem] = [
  {
    id: '3320ce84-c47c-592c-b599-6e968e12b0a3',
    code: 'ELECTRONIC_APPROVAL',
    name: '전자결재',
    features: [
      { id: '8c3ade64-251a-5b46-ba24-7bacb5384900', name: '문서 작성' },
      { id: '5b540d0d-ef38-58b3-bfe6-9cfb5b20109e', name: '문서 조회' },
      { id: '74c8f41e-e3a1-5828-a317-2ccca15895c7', name: '결재 상태' },
      { id: '1da6defb-2244-5cc5-ae70-bc75d8c5d4d4', name: '첨부 열람' },
    ],
  },
  {
    id: '8ae35be5-1857-58c0-a478-e05b4217ff95',
    code: 'COMMON_LOGIN',
    name: '공통 로그인',
    features: [
      { id: '213718e2-a478-56c0-a8ad-456b3607516c', name: '로그인·SSO' },
      { id: '0da3984c-7a69-5337-ba51-7b282f5f2659', name: '계정 접근' },
      { id: '7a361da4-7df4-592d-845f-75829318cd7c', name: '권한 신청' },
    ],
  },
  {
    id: '22d935d0-2ed8-58a7-a9b8-d73f717ed3c4',
    code: 'FILE_SHARING',
    name: '파일 공유',
    features: [
      { id: '152e8439-1e01-5e26-9749-f77df1cb65fc', name: '파일 열람' },
      { id: '06bb5aba-b278-5cc1-9e5b-41c38e674991', name: '파일 공유' },
      { id: 'b7b05865-02c9-5e39-a22e-d103b2a00057', name: '접근 권한' },
    ],
  },
];
export const users: [CurrentUser, CurrentUser, CurrentUser, CurrentUser] = [
  {
    id: 'f5b994d0-b857-5aba-bd46-2c49c7e8d1f0',
    employeeNo: 'DEMO-001',
    name: '가상 문의자',
    department,
    roles: [],
    primarySystemIds: [],
  },
  {
    id: '291f55b2-1546-58d8-81a4-bfa73a8d072e',
    employeeNo: 'DEMO-002',
    name: '가상 담당자',
    department,
    roles: [],
    primarySystemIds: [systems[0].id, systems[1].id],
  },
  {
    id: 'd521afcc-4b74-52db-ac36-aabe9fb59707',
    employeeNo: 'DEMO-003',
    name: '가상 업무 관리자',
    department,
    roles: ['BIZ_ADMIN'],
    primarySystemIds: [],
  },
  {
    id: 'ca1fd3bb-10f4-5504-94da-704b8a5f394b',
    employeeNo: 'DEMO-004',
    name: '가상 파일 담당자',
    department,
    roles: [],
    primarySystemIds: [systems[2].id],
  },
];
