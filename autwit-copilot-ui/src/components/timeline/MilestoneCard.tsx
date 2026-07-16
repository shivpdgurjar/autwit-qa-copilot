import type { Milestone } from '../../api/client';
import { Card, Mono, Muted } from '../ui';

const STATUS_STYLES: Record<string, string> = {
  pending: 'text-sky-300',
  complete: 'text-emerald-300',
  // Not green. A partial capture means the snapshot is incomplete, and comparing
  // against it can manufacture findings that look like product bugs.
  partial: 'text-amber-300',
  failed: 'text-red-300',
};

export function MilestoneCard({ milestone }: { milestone: Milestone }) {
  const cursor = milestone.event_cursor as Record<string, unknown> | undefined;
  const hasCursor = cursor && Object.keys(cursor).length > 0;

  return (
    <Card>
      <div className="flex items-center gap-2">
        <span className="text-xs">📍</span>
        <span className="text-sm font-medium">{milestone.name}</span>
        <span className={`text-[11px] font-medium ${STATUS_STYLES[milestone.status] ?? ''}`}>
          {milestone.status}
        </span>
      </div>

      {milestone.status === 'partial' && (
        <p className="mt-1 text-[11px] text-amber-200/70">
          Some parts were not captured. Comparisons against this snapshot may report rows as
          missing that were simply never read.
        </p>
      )}

      {milestone.note && <p className="mt-1 text-[12px] text-ink-300">{milestone.note}</p>}

      {/*
        Offsets, not timestamps. openapi.yaml: "Analysis reads offset windows, not time
        windows — time windows are lossy under load." Showing the cursor is how a tester
        can tell exactly which events a later capture will pick up from.
      */}
      {hasCursor && (
        <div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-[11px]">
          <Muted>cursor</Muted>
          {Object.entries(cursor).map(([topic, offsets]) => (
            <Mono key={topic} className="text-ink-300">
              {topic}: {JSON.stringify(offsets)}
            </Mono>
          ))}
        </div>
      )}
    </Card>
  );
}
