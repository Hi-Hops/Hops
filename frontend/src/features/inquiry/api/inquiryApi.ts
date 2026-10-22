import { useQuery } from '@tanstack/react-query';
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
