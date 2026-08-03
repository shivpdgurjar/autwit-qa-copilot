import { Badge } from '../../components/ui';
import type { AutomationRunStatus, AutomationRunSummary } from '../../api/client';

/**
 * Status colouring for an automation run.
 *
 * CANCELLED and TIMED_OUT are amber rather than red on purpose: only FAILED carries a
 * verdict about the system under test. Colouring all three red would make an evicted pod
 * look like a broken product.
 */
const TONES: Record<AutomationRunStatus, 'neutral' | 'sky' | 'red' | 'amber' | 'emerald'> = {
  QUEUED: 'neutral',
  RUNNING: 'sky',
  SUCCEEDED: 'emerald',
  FAILED: 'red',
  CANCELLED: 'amber',
  TIMED_OUT: 'amber',
};

export function AutomationStatusBadge({ status }: { status?: AutomationRunStatus }) {
  if (!status) {
    return null;
  }
  return <Badge tone={TONES[status] ?? 'neutral'}>{status.replace('_', ' ')}</Badge>;
}

/**
 * Scenario counts as a compact string.
 *
 * Skips are shown separately and never folded into failures — a skipped scenario is
 * paused awaiting data, not a defect, so a run of only skips reads as a pass.
 */
export function passRate(summary?: AutomationRunSummary): string {
  if (!summary || !summary.total) {
    return '—';
  }
  const parts = [`${summary.passed ?? 0}/${summary.total} passed`];
  if (summary.failed) {
    parts.push(`${summary.failed} failed`);
  }
  if (summary.skipped) {
    parts.push(`${summary.skipped} paused`);
  }
  return parts.join(' · ');
}
