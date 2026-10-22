export const paths = {
  home: '/',
  login: '/login',
  inquiries: '/inquiries',
  tickets: '/tickets',
  inquiryChat: (id: string) => `/inquiries/${encodeURIComponent(id)}/chat`,
  ticket: (id: string) => `/tickets/${encodeURIComponent(id)}`,
};
