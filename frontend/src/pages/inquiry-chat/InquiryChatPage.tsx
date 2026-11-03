import { useParams } from 'react-router';
import { SystemChatView } from '@/features/chat';

export function InquiryChatPage() {
  const { inquiryId } = useParams();
  return inquiryId ? <SystemChatView key={inquiryId} inquiryId={inquiryId} /> : null;
}
