import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import SessionRoute from './routes/sessions/SessionRoute';
import SessionListRoute from './routes/sessions/SessionListRoute';
import { AppShell } from './components/AppShell';
import PlanListRoute from './routes/plan/PlanListRoute';
import PlanRoute from './routes/plan/PlanRoute';
import '@fontsource-variable/inter';
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
          <Route element={<AppShell />}>
            <Route path="/" element={<Navigate to="/sessions" replace />} />
            <Route path="/sessions" element={<SessionListRoute />} />
            <Route path="/sessions/:sessionId" element={<SessionRoute />} />
            <Route path="/plan" element={<PlanListRoute />} />
            <Route path="/plan/:sessionId" element={<PlanRoute />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
