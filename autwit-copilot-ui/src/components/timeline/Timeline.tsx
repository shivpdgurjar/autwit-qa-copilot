import type { Milestone, Run, SessionDetail, Snapshot, Step } from '../../api/client';
import { isActive } from '../../api/client';
import { Card, EmptyState, Mono, Muted, Ago } from '../ui';
import { FailedRunCard, PendingRunCard } from '../chat/PendingRunCard';
import { MilestoneCard } from './MilestoneCard';
import { SnapshotCard } from './SnapshotCard';

/**
 * The session timeline, ordered by step seq.
 *
 * Everything here is derived from GET /sessions/{id} and nothing from an SSE payload —
 * that is what makes a dropped notification harmless (invariant 4).
 *
 * analysis steps are excluded: SKILL_CONTRACT §5 says notes "render in chat, not the
 * timeline". They are the running commentary, not the record.
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
  const steps = (session.steps ?? []).filter((s) => s.kind !== 'analysis');
  const activeByStep = new Map<string, Run>();
  (session.active_runs ?? []).forEach((r) => activeByStep.set(r.step_id, r));

  const milestoneByStep = new Map<string, Milestone>();
  (session.milestones ?? []).forEach((m) => m.step_id && milestoneByStep.set(m.step_id, m));

  const snapshotByMilestone = new Map<string, Snapshot>();
  (session.snapshots ?? []).forEach(
    (s) => s.milestone_id && snapshotByMilestone.set(s.milestone_id, s),
  );

  if (steps.length === 0) {
    return <EmptyState>Nothing yet. Say what you did and the copilot will capture it.</EmptyState>;
  }

  return (
    <ol className="space-y-2">
      {steps.map((step) => {
        const run = activeByStep.get(step.step_id);
        const milestone = milestoneByStep.get(step.step_id);
        const snapshot = milestone ? snapshotByMilestone.get(milestone.milestone_id) : undefined;

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
