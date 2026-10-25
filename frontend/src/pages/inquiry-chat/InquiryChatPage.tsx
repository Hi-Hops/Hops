import { useParams } from 'react-router';
import { ChatView } from '@/features/chat';

export function InquiryChatPage() {
  const { inquiryId } = useParams();
  return inquiryId ? <ChatView key={inquiryId} inquiryId={inquiryId} /> : null;
}
