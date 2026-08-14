import { useEffect, useState } from 'react';
import { useMarkMilestone, useSubmitMessage } from '../../hooks/useSubmitRun';
import { Button, Input, Mono, Textarea } from '../ui';

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

  // For now the tester drives the session with Skills + Milestones; the free-text chat
  // prompt is hidden. Flip SHOW_CHAT back to true to restore typed messages.
  const SHOW_CHAT: boolean = false;
  const SHOW_MILESTONES: boolean = true;

  return (
    <div className="border-t border-ink-700 p-2.5">
      {error != null && (
        <p className="mb-2 rounded border border-red-900/60 bg-red-950/20 px-2 py-1.5 text-[11px] text-red-300">
          {(error as { detail?: string }).detail ?? 'Could not submit.'}
        </p>
      )}

      {SHOW_MILESTONES && milestoneName !== null && (
        <div className="mb-2 flex items-center gap-1.5">
          <Input
            autoFocus
            value={milestoneName}
            onChange={(e) => setMilestoneName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') markMilestone();
              if (e.key === 'Escape') setMilestoneName(null);
            }}
            placeholder="milestone name, e.g. order_created"
            className="flex-1 py-1 font-mono text-[12px]"
          />
          <Button variant="ghost" size="sm" onClick={markMilestone}>
            Mark
          </Button>
        </div>
      )}

      {SHOW_CHAT && (
        <Textarea
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
          className="w-full resize-none text-sm"
        />
      )}

      <div className={`flex items-center gap-2 ${SHOW_CHAT ? 'mt-1.5' : ''}`}>
        {SHOW_MILESTONES && (
          <Button variant="ghost" size="sm" onClick={() => setMilestoneName('')}>
            📍 Milestone
          </Button>
        )}
        <Button variant="ghost" size="sm" onClick={onOpenPalette}>
          Skills
          <Mono className="rounded bg-ink-800 px-1 text-[10px] text-ink-400">⌘K</Mono>
        </Button>

        {SHOW_CHAT && (
          <Button
            size="sm"
            className="ml-auto"
            onClick={send}
            disabled={!text.trim() || submit.isPending}
          >
            Send
          </Button>
        )}
      </div>
    </div>
  );
}
