import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { sessionKey } from './useSession';
import type { StreamEventType } from '../api/client';

export type StreamStatus = 'connecting' | 'open' | 'closed';

/** What the API actually sends on the wire — thin by design. */
type StreamEvent = {
  type?: StreamEventType;
  session_id?: string;
  run_id?: string;
  step_id?: string;
  at?: string;
  message?: string;
};

/**
 * The refetch-on-notify pattern, which BUILD_BRIEF §3 calls out as the design itself:
 * "TanStack Query — server state; the refetch-on-notify pattern IS the design".
 *
 * So this hook deliberately does almost nothing. It never applies an event payload to
 * the cache — it invalidates and lets GET /sessions/{id} answer. That is what makes a
 * dropped notification harmless (invariant 4), and it is the whole reason the SSE
 * events are allowed to be thin.
 *
 * The temptation is to patch the cache from the payload and save a request. Resist it:
 * the moment the UI trusts an event, a missed one leaves the screen quietly wrong, and
 * a QA tool that is quietly wrong is worse than one that is slow.
 *
 * @param onNote analysis.note events render in chat rather than the timeline
 *               (SKILL_CONTRACT §5), so they are handed to the caller as well as
 *               triggering a refetch.
 */
export function useSessionStream(
  sessionId: string,
  onNote?: (event: StreamEvent) => void,
): { status: StreamStatus; lastEvent: StreamEvent | null } {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<StreamStatus>('connecting');
  const [lastEvent, setLastEvent] = useState<StreamEvent | null>(null);

  // Kept in a ref so a changing callback identity does not tear down the stream.
  const onNoteRef = useRef(onNote);
  onNoteRef.current = onNote;

  useEffect(() => {
    const source = new EventSource(`/api/v1/sessions/${sessionId}/stream`);

    const refetch = (event: MessageEvent) => {
      let parsed: StreamEvent = {};
      try {
        parsed = JSON.parse(event.data) as StreamEvent;
      } catch {
        // A hint we cannot parse is still a hint that something happened.
      }
      setLastEvent(parsed);

      if (parsed.type === 'analysis.note') {
        onNoteRef.current?.(parsed);
      }

      // The only thing any event does.
      void queryClient.invalidateQueries({ queryKey: sessionKey(sessionId) });
    };

    const types: StreamEventType[] = [
      'run.queued',
      'run.started',
      'run.progress',
      'run.succeeded',
      'run.failed',
      'run.timed_out',
      'run.cancelled',
      'finding.raised',
      'analysis.note',
      'session.ended',
    ];
    types.forEach((type) => source.addEventListener(type, refetch));

    source.addEventListener('stream.open', () => setStatus('open'));
    source.onopen = () => setStatus('open');

    source.onerror = () => {
      // EventSource reconnects on its own. Reporting 'closed' is what turns the poll
      // fallback on, so a browser that never recovers still shows live data.
      setStatus(source.readyState === EventSource.CLOSED ? 'closed' : 'connecting');
    };

    return () => {
      source.close();
      setStatus('closed');
    };
  }, [sessionId, queryClient]);

  return { status, lastEvent };
}
