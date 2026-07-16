import type { Snapshot } from '../../api/client';
import { Card, Mono, Muted } from '../ui';

/**
 * A captured snapshot and its parts.
 *
 * part_key is shown for every part, deliberately. It is the join key comparison runs
 * on, and SKILL_CONTRACT §6.2 calls it "the single most important field in the
 * contract" — if it ever drifts between two captures of the same scope, the diff
 * engine reports phantom added/removed parts. Putting the keys on screen is what lets
 * a tester notice that with their own eyes.
 */
export function SnapshotCard({
  snapshot,
  onClick,
}: {
  snapshot: Snapshot;
  onClick?: () => void;
}) {
  const parts = snapshot.parts ?? [];
  const rows = parts.reduce((sum, p) => sum + (p.row_count ?? 0), 0);

  return (
    <Card onClick={onClick}>
      <div className="flex items-center gap-2">
        <span className="text-xs">📦</span>
        <span className="text-sm font-medium">{snapshot.label}</span>
        <Mono className="rounded bg-ink-800 px-1.5 py-0.5 text-ink-300">{snapshot.scope}</Mono>
        {snapshot.status !== 'complete' && (
          <span className="text-[11px] font-medium text-amber-300">{snapshot.status}</span>
        )}
        <span className="ml-auto text-[11px]">
          <Muted>
            {parts.length} part{parts.length === 1 ? '' : 's'} · {rows} row{rows === 1 ? '' : 's'}
          </Muted>
        </span>
      </div>

      <div className="mt-2 flex flex-wrap gap-1">
        {parts.map((part) => (
          <Mono
            key={part.part_key}
            className="rounded border border-ink-700 bg-ink-850 px-1.5 py-0.5 text-ink-300"
            // The row count matters here: an empty part and a missing part mean very
            // different things, and only one of them is a bug.
          >
            {part.part_key}
            <span className="ml-1 text-ink-400">{part.row_count ?? '—'}</span>
          </Mono>
        ))}
      </div>
    </Card>
  );
}
