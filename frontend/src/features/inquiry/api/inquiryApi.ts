import { useInfiniteQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { getSystemInquiries } from './systemInquiryApi';
import type { InquiryStatus, InquirySummary, Page } from '@/shared/api/types/hopsSystem1';

function getNextPageParam(last: Page<InquirySummary>) {
  return last.items.length > 0 && (last.page + 1) * last.size < last.totalElements
    ? last.page + 1
    : undefined;
}

export function useInquiries(page: number, status?: InquiryStatus) {
  const userId = useSession((state) => state.user?.id);
  return useInfiniteQuery({
    queryKey: ['inquiries', userId, 'list', page, status],
    initialPageParam: page,
    queryFn: ({ pageParam, signal }) => getSystemInquiries(pageParam, signal, status),
    refetchInterval: 5000,
    getNextPageParam,
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
    getNextPageParam,
    enabled: Boolean(userId),
  });
}
