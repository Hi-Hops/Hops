import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/app/App';
import '@/shared/styles/tokens.css';
import '@/shared/styles/global.css';

const root = document.getElementById('root');
if (!root) throw new Error('앱을 표시할 영역이 없어요.');
createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
