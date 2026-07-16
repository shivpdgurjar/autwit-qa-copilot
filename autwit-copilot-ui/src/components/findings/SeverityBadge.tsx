import type { Severity, Verdict } from '../../api/client';

/**
 * Severity is the scale in openapi.yaml: info | low | medium | high | critical.
 *
 * Note there is deliberately no 'warn' here — warn is a Verdict, not a Severity. The
 * API normalises any off-scale value it receives to medium before storing it, so this
 * component never sees one (see CONTRACT_RATIFICATION_REQUEST.md Q4).
 */
const SEVERITY_STYLES: Record<Severity, string> = {
  critical: 'bg-red-950 text-red-300 ring-red-900',
  high: 'bg-orange-950 text-orange-300 ring-orange-900',
  medium: 'bg-amber-950 text-amber-300 ring-amber-900',
  low: 'bg-ink-800 text-ink-300 ring-ink-700',
  info: 'bg-ink-800 text-ink-400 ring-ink-700',
};

export function SeverityBadge({ severity }: { severity: Severity }) {
  return (
    <span
      className={`inline-flex shrink-0 items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide ring-1 ring-inset ${SEVERITY_STYLES[severity]}`}
    >
      {severity}
    </span>
  );
}

const VERDICT_STYLES: Record<Verdict, string> = {
  pass: 'bg-emerald-950 text-emerald-300 ring-emerald-900',
  fail: 'bg-red-950 text-red-300 ring-red-900',
  warn: 'bg-amber-950 text-amber-300 ring-amber-900',
  // Grey, not green. An inconclusive comparison is not a mild pass -- it means a part
  // could not be read at all, and it must not look reassuring.
  inconclusive: 'bg-ink-800 text-ink-300 ring-ink-600',
};

export function VerdictBadge({ verdict }: { verdict: Verdict }) {
  return (
    <span
      className={`inline-flex items-center rounded px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide ring-1 ring-inset ${VERDICT_STYLES[verdict]}`}
    >
      {verdict}
    </span>
  );
}
