/** 경로 문자열을 기능·페이지에 복제하지 않기 위한 공용 경로 생성기. 실제 route 정의는 app/router.tsx다. */
export const paths = {
  home: '/',
  login: '/login',
  inquiries: '/inquiries',
  tickets: '/tickets',
  adminTickets: '/admin/tickets',
  inquiryChat: (id: string) => `/inquiries/${encodeURIComponent(id)}/chat`,
  ticket: (id: string) => `/tickets/${encodeURIComponent(id)}`,
};
