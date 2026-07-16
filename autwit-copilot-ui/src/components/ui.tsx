import type { ReactNode } from 'react';

export function Card({
  children,
  onClick,
  className = '',
}: {
  children: ReactNode;
  onClick?: () => void;
  className?: string;
}) {
  const interactive = onClick
    ? 'cursor-pointer hover:border-ink-600 hover:bg-ink-850 text-left w-full'
    : '';
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      onClick={onClick}
      className={`rounded-lg border border-ink-700 bg-ink-900 p-3 transition-colors ${interactive} ${className}`}
    >
      {children}
    </Tag>
  );
}

/** Extends span props so callers keep title, aria-*, and the rest. */
type SpanProps = React.ComponentPropsWithoutRef<'span'>;

export function Mono({ children, className = '', ...rest }: SpanProps) {
  return (
    <span className={`font-mono text-[12px] ${className}`} {...rest}>
      {children}
    </span>
  );
}

export function Muted({ children, className = '', ...rest }: SpanProps) {
  return (
    <span className={`text-ink-400 ${className}`} {...rest}>
      {children}
    </span>
  );
}

/** Relative time, because "2m ago" is what a tester actually wants mid-session. */
export function Ago({ at }: { at?: string }) {
  if (!at) return null;
  const seconds = Math.max(0, (Date.now() - new Date(at).getTime()) / 1000);
  const label =
    seconds < 60
      ? `${Math.floor(seconds)}s ago`
      : seconds < 3600
        ? `${Math.floor(seconds / 60)}m ago`
        : seconds < 86400
          ? `${Math.floor(seconds / 3600)}h ago`
          : new Date(at).toLocaleDateString();
  return (
    <time dateTime={at} title={new Date(at).toLocaleString()} className="text-ink-400">
      {label}
    </time>
  );
}

/**
 * Elapsed time, never a progress bar.
 *
 * openapi.yaml on Run.elapsed_ms: "Server-computed. UI shows elapsed time, never a
 * fake progress bar." A snapshot capture takes anywhere from 5s to 10 minutes and the
 * server has no idea which -- a bar that invents a percentage is a lie that erodes
 * trust in everything else on the screen.
 */
export function Elapsed({ ms }: { ms?: number }) {
  if (ms === undefined) return null;
  const s = Math.floor(ms / 1000);
  return (
    <Mono className="text-ink-400 tabular-nums">
      {s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, '0')}s`}
    </Mono>
  );
}

export function Spinner({ className = '' }: { className?: string }) {
  return (
    <svg className={`size-3.5 animate-spin ${className}`} viewBox="0 0 24 24" fill="none">
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" className="opacity-25" />
      <path
        d="M12 2a10 10 0 0 1 10 10"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
    </svg>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="px-3 py-6 text-center text-sm text-ink-400 italic">{children}</p>;
}
