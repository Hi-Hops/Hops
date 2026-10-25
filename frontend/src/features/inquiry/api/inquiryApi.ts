import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { apiRequest } from '@/shared/api/client';
import type { InquiryPage } from '@/shared/api/types';
import { useSession } from '@/features/auth';

export function useInquiries(page = 0) {
  const userId = useSession((state) => state.user?.id);
  return useQuery({
    queryKey: ['inquiries', userId, page],
    queryFn: () => apiRequest<InquiryPage>(`/api/inquiries?page=${String(page)}&size=20`),
    enabled: Boolean(userId),
  });
}

export function useInquirySessions() {
  const userId = useSession((state) => state.user?.id);
  return useInfiniteQuery({
    queryKey: ['inquiries', userId, 'sessions'],
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      apiRequest<InquiryPage>(`/api/inquiries?page=${String(pageParam)}&size=20`),
    getNextPageParam: (last) =>
      last.items.length > 0 && (last.page + 1) * last.size < last.totalElements
        ? last.page + 1
        : undefined,
    enabled: Boolean(userId),
  });
}
