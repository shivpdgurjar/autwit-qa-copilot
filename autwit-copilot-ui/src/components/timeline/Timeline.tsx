import type { Milestone, Run, SessionDetail, Snapshot, Step } from '../../api/client';
import { isActive } from '../../api/client';
import { Card, EmptyState, Mono, Muted, Ago } from '../ui';
import { FailedRunCard, PendingRunCard } from '../chat/PendingRunCard';
import { MilestoneCard } from './MilestoneCard';
import { SnapshotCard } from './SnapshotCard';

/**
 * The session timeline: the record of what was captured.
 *
 * Everything here is derived from GET /sessions/{id} and nothing from an SSE payload —
 * that is what makes a dropped notification harmless (invariant 4).
 *
 * The chat column owns the conversation, so this excludes:
 * - analysis — SKILL_CONTRACT §5: notes "render in chat, not the timeline"
 * - user_utterance — it is already a chat bubble, and showing the same sentence twice
 *   side by side just makes both columns harder to scan
 *
 * What is left is what the session actually produced: milestones, snapshots,
 * comparisons and the skills that ran. The server-rendered report still carries every
 * step, so nothing is lost from the record itself.
 */
export function Timeline({
  session,
  onOpenSnapshot,
  onCancelRun,
}: {
  session: SessionDetail;
  onOpenSnapshot: (snapshot: Snapshot) => void;
  onCancelRun?: (runId: string) => void;
}) {
  const steps = (session.steps ?? []).filter(
    (s) => s.kind !== 'analysis' && s.kind !== 'user_utterance',
  );
  const activeByStep = new Map<string, Run>();
  (session.active_runs ?? []).forEach((r) => activeByStep.set(r.step_id, r));

  const milestoneByStep = new Map<string, Milestone>();
  (session.milestones ?? []).forEach((m) => m.step_id && milestoneByStep.set(m.step_id, m));

  /**
   * Keyed by step_id, not milestone_id.
   *
   * A snapshot captured straight from the ⌘K palette has no milestone — it is a
   * skill_execute run, not a milestone run. Indexing by milestone_id made those
   * snapshots invisible: the tester runs snapshot.capture, nine artifacts land, and
   * the timeline shows nothing. Every snapshot has a step_id; that is the honest key.
   */
  const snapshotByStep = new Map<string, Snapshot>();
  (session.snapshots ?? []).forEach((s) => s.step_id && snapshotByStep.set(s.step_id, s));

  if (steps.length === 0) {
    return <EmptyState>Nothing yet. Say what you did and the copilot will capture it.</EmptyState>;
  }

  return (
    <ol className="space-y-2">
      {steps.map((step) => {
        const run = activeByStep.get(step.step_id);
        const milestone = milestoneByStep.get(step.step_id);
        const snapshot = snapshotByStep.get(step.step_id);

        return (
          <li key={step.step_id} className="space-y-2">
            <StepCard step={step} />

            {milestone && (
              <div className="ml-4">
                <MilestoneCard milestone={milestone} />
              </div>
            )}

            {snapshot && (
              <div className="ml-4">
                <SnapshotCard snapshot={snapshot} onClick={() => onOpenSnapshot(snapshot)} />
              </div>
            )}

            {/* An in-flight run: rendered from active_runs, which the 202 made knowable
                before the work started. */}
            {run && isActive(run.status) && (
              <div className="ml-4">
                <PendingRunCard run={run} onCancel={onCancelRun} />
              </div>
            )}
            {run && (run.status === 'failed' || run.status === 'timed_out') && (
              <div className="ml-4">
                <FailedRunCard run={run} />
              </div>
            )}
          </li>
        );
      })}
    </ol>
  );
}

const KIND_LABEL: Record<string, string> = {
  user_utterance: 'said',
  skill_invocation: 'skill',
  milestone: 'milestone',
  system: 'system',
  analysis: 'analysis',
};

function StepCard({ step }: { step: Step }) {
  const isUser = step.actor === 'user';

  return (
    <Card className={isUser ? 'border-ink-600' : ''}>
      <div className="flex items-baseline gap-2">
        <Mono className="text-ink-400 tabular-nums">#{step.seq}</Mono>
        <Muted className="text-[11px] uppercase tracking-wide">
          {KIND_LABEL[step.kind] ?? step.kind}
        </Muted>
        <StepStatus status={step.status} />
        <span className="ml-auto text-[11px]">
          <Ago at={step.started_at} />
        </span>
      </div>
      <p className={`mt-1 text-sm ${isUser ? 'text-ink-100' : 'text-ink-300'}`}>{step.label}</p>
    </Card>
  );
}

function StepStatus({ status }: { status: Step['status'] }) {
  if (status === 'succeeded') return null; // the default; badging it is noise
  const styles: Record<string, string> = {
    pending: 'text-sky-400',
    running: 'text-sky-300',
    failed: 'text-red-400',
    skipped: 'text-ink-400',
  };
  return (
    <span className={`text-[11px] font-medium ${styles[status] ?? 'text-ink-400'}`}>{status}</span>
  );
}
