import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import SessionRoute from './routes/sessions/SessionRoute';
import SessionListRoute from './routes/sessions/SessionListRoute';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // The stream drives freshness; refocus-refetching on top of that is noise.
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Navigate to="/sessions" replace />} />
          <Route path="/sessions" element={<SessionListRoute />} />
          <Route path="/sessions/:sessionId" element={<SessionRoute />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
