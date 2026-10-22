import { RouterProvider, createBrowserRouter } from 'react-router';
import { AppProviders } from './providers';
import { ErrorPage } from '@/pages/errors/ErrorPage';

const router = createBrowserRouter([
  {
    path: '/',
    element: (
      <main>
        <h1>Hops</h1>
        <p>사내 IT 지원</p>
      </main>
    ),
  },
  { path: '*', element: <ErrorPage /> },
]);

export function App() {
  return (
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  );
}
