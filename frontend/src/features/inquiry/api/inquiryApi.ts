import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { getSystemInquiries } from './systemInquiryApi';

export function useInquiries(page = 0) {
  const userId = useSession((state) => state.user?.id);
  return useQuery({
    queryKey: ['inquiries', userId, page],
    queryFn: ({ signal }) => getSystemInquiries(page, signal),
    refetchInterval: 5000,
    enabled: Boolean(userId),
  });
}

export function useInquirySessions() {
  const userId = useSession((state) => state.user?.id);
  return useInfiniteQuery({
    queryKey: ['inquiries', userId, 'sessions'],
    initialPageParam: 0,
    queryFn: ({ pageParam, signal }) => getSystemInquiries(pageParam, signal),
    refetchInterval: 5000,
    getNextPageParam: (last) =>
      last.items.length > 0 && (last.page + 1) * last.size < last.totalElements
        ? last.page + 1
        : undefined,
    enabled: Boolean(userId),
  });
}
