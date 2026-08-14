import type { Finding, Severity } from '../../api/client';
import { EmptyState, Mono, Muted } from '../ui';
import { SeverityBadge } from './SeverityBadge';

/** Worst first. A feed nobody scrolls must lead with what matters. */
const ORDER: Severity[] = ['critical', 'high', 'medium', 'low', 'info'];

export function FindingsFeed({ findings }: { findings: Finding[] }) {
  if (findings.length === 0) {
    return <EmptyState>No findings yet.</EmptyState>;
  }

  const sorted = [...findings].sort((a, b) => ORDER.indexOf(a.severity!) - ORDER.indexOf(b.severity!));

  return (
    <ul className="divide-y divide-ink-800">
      {sorted.map((finding) => (
        <li key={finding.finding_id} className="px-3 py-2.5">
          <div className="flex items-start gap-2">
            <SeverityBadge severity={finding.severity!} />
            <div className="min-w-0 flex-1">
              <FindingMessage message={finding.message} />

              <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px]">
                {finding.category && <Muted>{finding.category}</Muted>}
                {finding.part_key && (
                  <Mono className="text-ink-400">{finding.part_key}</Mono>
                )}
                {finding.entity_key && (
                  <Mono className="text-ink-400">{finding.entity_key}</Mono>
                )}
              </div>

              {/* Before/after only when the finding actually carries a value. A field
                  name alone isn't enough: analyser findings (cross-representation,
                  classification) name a field but put expected/actual in the message
                  prose, leaving before/after null -- rendering those as 'null → null' is
                  noise, so suppress the row unless there's a real value to show. */}
              {(finding.before_value != null || finding.after_value != null) && (
                <div className="mt-1 flex items-center gap-1.5 text-[11px]">
                  <Mono className="rounded bg-red-950/40 px-1 py-0.5 text-red-300/90">
                    {stringify(finding.before_value)}
                  </Mono>
                  <span className="text-ink-400">→</span>
                  <Mono className="rounded bg-emerald-950/40 px-1 py-0.5 text-emerald-300/90">
                    {stringify(finding.after_value)}
                  </Mono>
                </div>
              )}
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}

export function FindingCounts({ counts }: { counts?: Record<string, number> }) {
  if (!counts) return null;
  const present = ORDER.filter((s) => (counts[s] ?? 0) > 0);
  if (present.length === 0) return null;

  return (
    <div className="flex items-center gap-1.5">
      {present.map((severity) => (
        <span key={severity} className="flex items-center gap-1">
          <SeverityBadge severity={severity} />
          <Mono className="text-ink-300 tabular-nums">{counts[severity]}</Mono>
        </span>
      ))}
    </div>
  );
}

/**
 * A finding's message as bullets. The analyser writes each explanation as one claim per
 * line, so a multi-point message renders as a list rather than a wall of prose; a single
 * line (deterministic findings) stays a plain paragraph so it does not read as a lone dot.
 */
function FindingMessage({ message }: { message?: string | null }) {
  const points = toBullets(message ?? '');
  if (points.length <= 1) {
    return <p className="text-[12px] leading-snug text-ink-200">{message}</p>;
  }
  return (
    <ul className="list-disc space-y-0.5 pl-4 text-[12px] leading-snug text-ink-200">
      {points.map((point, i) => (
        <li key={i}>{point}</li>
      ))}
    </ul>
  );
}

/** Split on line breaks and strip any leading list marker the model may have emitted. */
function toBullets(message: string): string[] {
  return message
    .split(/\r?\n/)
    .map((line) => line.trim().replace(/^[-*•]\s*/, '').trim())
    .filter((line) => line.length > 0);
}

function stringify(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
