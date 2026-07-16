import { useEffect, useRef, useState } from 'react';
import { useMarkMilestone, useSubmitMessage } from '../../hooks/useSubmitRun';
import { Mono } from '../ui';

/**
 * The chat composer — the thing a tester actually drives the session with.
 *
 * Submit-only: this returns the moment the API says 202, and the pending card appears
 * from the refetch. It never waits for a snapshot capture that may take ten minutes.
 */
export function Composer({
  sessionId,
  disabled,
  onOpenPalette,
}: {
  sessionId: string;
  disabled?: boolean;
  onOpenPalette: () => void;
}) {
  const [text, setText] = useState('');
  const [milestoneName, setMilestoneName] = useState<string | null>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const submit = useSubmitMessage(sessionId);
  const milestone = useMarkMilestone(sessionId);

  // ⌘K / Ctrl+K anywhere in the session opens the palette.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        onOpenPalette();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onOpenPalette]);

  const send = () => {
    const message = text.trim();
    if (!message || disabled) return;
    // Cleared optimistically: the tester's next thought should not wait on a round trip.
    setText('');
    submit.mutate({ message }, { onError: () => setText(message) });
  };

  const markMilestone = () => {
    const name = milestoneName?.trim();
    if (!name) return;
    setMilestoneName(null);
    milestone.mutate({ name });
  };

  const error = submit.error ?? milestone.error;

  if (disabled) {
    return (
      <div className="border-t border-ink-700 px-3 py-3 text-center text-[12px] text-ink-400 italic">
        This session has ended. Its report is on the record.
      </div>
    );
  }

  return (
    <div className="border-t border-ink-700 p-2.5">
      {error != null && (
        <p className="mb-2 rounded border border-red-900/60 bg-red-950/20 px-2 py-1.5 text-[11px] text-red-300">
          {(error as { detail?: string }).detail ?? 'Could not submit.'}
        </p>
      )}

      {milestoneName !== null && (
        <div className="mb-2 flex items-center gap-1.5">
          <input
            autoFocus
            value={milestoneName}
            onChange={(e) => setMilestoneName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') markMilestone();
              if (e.key === 'Escape') setMilestoneName(null);
            }}
            placeholder="milestone name, e.g. order_created"
            className="flex-1 rounded border border-ink-700 bg-ink-950 px-2 py-1 font-mono text-[12px] outline-none focus:border-sky-700"
          />
          <button
            onClick={markMilestone}
            className="rounded bg-ink-700 px-2 py-1 text-[11px] hover:bg-ink-600"
          >
            Mark
          </button>
        </div>
      )}

      <textarea
        ref={inputRef}
        rows={2}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          // Enter sends, Shift+Enter newlines. A tester narrating a flow types short
          // lines and expects them to go.
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            send();
          }
        }}
        placeholder="Say what you did — “I created order XXXX”"
        className="w-full resize-none rounded border border-ink-700 bg-ink-950 px-2.5 py-2 text-sm outline-none placeholder:text-ink-400 focus:border-sky-700"
      />

      <div className="mt-1.5 flex items-center gap-2">
        <button
          onClick={() => setMilestoneName('')}
          className="rounded border border-ink-700 px-2 py-1 text-[11px] text-ink-300 hover:border-ink-600 hover:text-ink-100"
        >
          📍 Milestone
        </button>
        <button
          onClick={onOpenPalette}
          className="flex items-center gap-1.5 rounded border border-ink-700 px-2 py-1 text-[11px] text-ink-300 hover:border-ink-600 hover:text-ink-100"
        >
          Skills
          <Mono className="rounded bg-ink-800 px-1 text-[10px] text-ink-400">⌘K</Mono>
        </button>

        <button
          onClick={send}
          disabled={!text.trim() || submit.isPending}
          className="ml-auto rounded bg-sky-700 px-3 py-1 text-[12px] font-medium text-white hover:bg-sky-600 disabled:opacity-40"
        >
          Send
        </button>
      </div>
    </div>
  );
}
