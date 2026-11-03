import { useParams } from 'react-router';
import { CaseDetailView, CaseList } from '@/features/case';
export function CasesPage() {
  const { caseId } = useParams();
  return caseId ? <CaseDetailView key={caseId} id={caseId} /> : <CaseList />;
}
