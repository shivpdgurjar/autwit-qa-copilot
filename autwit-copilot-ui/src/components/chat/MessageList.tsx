import { useEffect, useRef } from 'react';
import type { Run, SessionDetail } from '../../api/client';
import { isActive } from '../../api/client';
import { Ago, EmptyState, Muted } from '../ui';
import { FailedRunCard, PendingRunCard } from './PendingRunCard';

/**
 * The chat column: what the tester said, and what the copilot said back.
 *
 * Only these kinds appear here. SKILL_CONTRACT §5 puts analysis notes in chat
 * explicitly — "Renders in chat, not the timeline" — because they are the copilot
 * narrating its own work, not part of the record. Milestones and snapshots are the
 * record, and live in the Timeline.
 */
export function MessageList({
  session,
  onCancelRun,
}: {
  session: SessionDetail;
  onCancelRun?: (runId: string) => void;
}) {
  const bottom = useRef<HTMLDivElement>(null);
  const steps = (session.steps ?? []).filter(
    (s) => s.kind === 'user_utterance' || s.kind === 'analysis' || s.kind === 'skill_invocation',
  );

  const runByStep = new Map<string, Run>();
  (session.active_runs ?? []).forEach((r) => runByStep.set(r.step_id, r));

  // Follow the conversation as it grows, the way a chat should.
  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [steps.length, session.active_runs?.length]);

  if (steps.length === 0) {
    return (
      <EmptyState>
        Tell the copilot what you did and it will capture the state around it.
      </EmptyState>
    );
  }

  return (
    <div className="space-y-2.5 p-3">
      {steps.map((step) => {
        const run = runByStep.get(step.step_id);

        if (step.kind === 'user_utterance') {
          return (
            <div key={step.step_id} className="flex justify-end">
              <div className="max-w-[85%] rounded-lg rounded-br-sm bg-sky-900/50 px-3 py-2 ring-1 ring-sky-800/60">
                <p className="text-sm text-ink-100">{step.label}</p>
                <span className="mt-0.5 block text-right text-[10px]">
                  <Ago at={step.started_at} />
                </span>
              </div>
            </div>
          );
        }

        if (step.kind === 'analysis') {
          return (
            <div key={step.step_id} className="max-w-[90%]">
              <div className="rounded-lg rounded-bl-sm border border-ink-700 bg-ink-900 px-3 py-2">
                <p className="text-[13px] leading-snug text-ink-200">{step.label}</p>
              </div>
            </div>
          );
        }

        // skill_invocation: the agent's work. While it runs this is the pending card
        // the 202's step_id made possible; once done it names what actually ran.
        return (
          <div key={step.step_id} className="max-w-[90%] space-y-1.5">
            {run && isActive(run.status) ? (
              <PendingRunCard run={run} onCancel={onCancelRun} />
            ) : run && (run.status === 'failed' || run.status === 'timed_out') ? (
              <FailedRunCard run={run} />
            ) : (
              <div className="flex items-center gap-2 px-1">
                <Muted className="text-[11px]">ran</Muted>
                <span className="font-mono text-[11px] text-ink-300">{step.label}</span>
                {step.status === 'failed' && (
                  <span className="text-[11px] text-red-400">failed</span>
                )}
              </div>
            )}
          </div>
        );
      })}
      <div ref={bottom} />
    </div>
  );
}
