import { createBrowserRouter } from 'react-router';
import { RequireAuth } from './guards/RequireAuth';
import { AppLayout } from './layouts/AppLayout';
import { LoginPage } from '@/pages/login/LoginPage';
import { HomePage } from '@/pages/home/HomePage';
import { TicketsPage } from '@/pages/tickets/TicketsPage';
import { ErrorPage } from '@/pages/errors/ErrorPage';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/', element: <HomePage /> },
          { path: '/tickets', element: <TicketsPage /> },
          { path: '*', element: <ErrorPage /> },
        ],
      },
    ],
  },
]);
