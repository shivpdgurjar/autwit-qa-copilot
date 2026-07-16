import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, unwrap } from '../../api/client';
import { Card, Mono, Muted, Spinner } from '../ui';

/**
 * The events captured in a session.
 *
 * Fetched from GET /sessions/{id}/events rather than GET /sessions/{id}: a session can
 * hold thousands, so the session endpoint carries only a count and this paginates.
 *
 * Offsets are shown, not just times. openapi.yaml on event_cursor: "Analysis reads
 * offset windows, not time windows — time windows are lossy under load." The offset is
 * what tells a tester exactly which events a later capture will pick up from, and it is
 * what the dedupe is keyed on.
 */
export function EventBatchCard({ sessionId, count }: { sessionId: string; count: number }) {
  const [open, setOpen] = useState(false);

  if (count === 0) return null;

  return (
    <section className="mt-4">
      <button
        onClick={() => setOpen((v) => !v)}
        className="mb-1.5 flex w-full items-center gap-2 text-left"
      >
        <h2 className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">Events</h2>
        <Mono className="text-ink-300 tabular-nums">{count}</Mono>
        <span className="text-[10px] text-ink-400">{open ? '▾' : '▸'}</span>
      </button>
      {open && <EventList sessionId={sessionId} />}
    </section>
  );
}

function EventList({ sessionId }: { sessionId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['events', sessionId],
    queryFn: async ({ signal }) =>
      unwrap(
        await api.GET('/sessions/{sessionId}/events', {
          params: { path: { sessionId }, query: { limit: 50 } },
          signal,
        }),
      ),
  });

  if (isLoading) {
    return (
      <p className="flex items-center gap-2 p-3 text-[12px] text-ink-400">
        <Spinner /> Loading events…
      </p>
    );
  }

  const events = data?.events ?? [];

  return (
    <Card className="p-0">
      <table className="w-full text-[11px]">
        <thead>
          <tr className="text-ink-400">
            <th className="px-2.5 py-1.5 text-left font-medium">offset</th>
            <th className="px-2.5 py-1.5 text-left font-medium">type</th>
            <th className="px-2.5 py-1.5 text-left font-medium">topic</th>
            <th className="px-2.5 py-1.5 text-left font-medium">key</th>
            <th className="px-2.5 py-1.5 text-left font-medium">occurred</th>
          </tr>
        </thead>
        <tbody>
          {events.map((event) => (
            <tr key={event.event_id} className="border-t border-ink-800">
              <td className="px-2.5 py-1">
                <Mono className="text-ink-300 tabular-nums">{event.source_offset ?? '—'}</Mono>
              </td>
              <td className="px-2.5 py-1 text-ink-200">{event.event_type}</td>
              <td className="px-2.5 py-1">
                <Mono className="text-ink-400">{event.topic}</Mono>
              </td>
              <td className="px-2.5 py-1">
                <Mono className="text-ink-400">{event.event_key ?? '—'}</Mono>
              </td>
              <td className="px-2.5 py-1">
                <Muted>
                  {event.occurred_at ? new Date(event.occurred_at).toLocaleTimeString() : '—'}
                </Muted>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {data?.next_cursor && (
        // Honest rather than silently truncating: the count in the header is the truth,
        // and this says the table is not all of it.
        <p className="border-t border-ink-800 px-2.5 py-1.5 text-[10px] text-ink-400 italic">
          Showing the first {events.length}. More were captured.
        </p>
      )}
    </Card>
  );
}
