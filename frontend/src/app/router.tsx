import { RequireRole } from './guards/RequireRole';
import { ForbiddenPage } from '@/pages/errors/ForbiddenPage';
import { createBrowserRouter } from 'react-router';
import { RequireAuth } from './guards/RequireAuth';
import { AppLayout } from './layouts/AppLayout';
import { LoginPage } from '@/pages/login/LoginPage';
import { HomePage } from '@/pages/home/HomePage';
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
          {
            path: '/inquiries/:inquiryId/chat',
            lazy: () =>
              import('@/pages/inquiry-chat/InquiryChatPage').then(({ InquiryChatPage }) => ({
                Component: InquiryChatPage,
              })),
          },
          {
            path: '/inquiries',
            lazy: () =>
              import('@/pages/inquiries/InquiriesPage').then(({ InquiriesPage }) => ({
                Component: InquiriesPage,
              })),
          },
          {
            path: '/tickets/:ticketId?',
            lazy: () =>
              import('@/pages/tickets/TicketsPage').then(({ TicketsPage }) => ({
                Component: TicketsPage,
              })),
          },
          {
            element: <RequireRole role="BIZ_ADMIN" />,
            children: [
              {
                path: '/admin/tickets/:ticketId?',
                lazy: () =>
                  import('@/pages/tickets/TicketsPage').then(({ TicketsPage }) => ({
                    Component: TicketsPage,
                  })),
              },
            ],
          },
          { path: '/403', element: <ForbiddenPage /> },
          { path: '*', element: <ErrorPage /> },
        ],
      },
    ],
  },
]);
