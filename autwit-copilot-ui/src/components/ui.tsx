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
      className={`rounded-xl border border-ink-700 bg-ink-900 p-3 shadow-sm transition-colors ${interactive} ${className}`}
    >
      {children}
    </Tag>
  );
}

/**
 * The one button in the app. Variants: primary (Acuver blue, solid), ghost (bordered, on
 * white), warn (amber-bordered, for "proceed anyway"-style overrides). Replaces the
 * hand-rolled `<button className="rounded bg-sky-700…">` that used to differ per screen.
 */
type ButtonProps = React.ComponentPropsWithoutRef<'button'> & {
  variant?: 'primary' | 'ghost' | 'warn';
  size?: 'sm' | 'md';
};

export function Button({ variant = 'primary', size = 'md', className = '', ...rest }: ButtonProps) {
  const base =
    'inline-flex items-center justify-center gap-2 rounded-lg font-semibold transition-colors ' +
    'active:translate-y-px disabled:pointer-events-none disabled:opacity-40';
  const sizes = { sm: 'px-3 py-1.5 text-[12px]', md: 'px-4 py-2 text-[13px]' };
  const variants = {
    primary: 'bg-sky-600 text-white shadow-sm hover:bg-sky-500',
    ghost: 'border border-ink-600 bg-ink-900 text-ink-100 hover:border-ink-400 hover:bg-ink-850',
    warn: 'border border-amber-900 bg-ink-900 text-amber-300 hover:bg-amber-950',
  };
  return <button className={`${base} ${sizes[size]} ${variants[variant]} ${className}`} {...rest} />;
}

/** A small status/label chip. `tone` carries semantic meaning (severity, state), not just color. */
type BadgeTone = 'neutral' | 'sky' | 'red' | 'amber' | 'emerald';

export function Badge({
  tone = 'neutral',
  children,
  className = '',
}: {
  tone?: BadgeTone;
  children: ReactNode;
  className?: string;
}) {
  const tones: Record<BadgeTone, string> = {
    neutral: 'bg-ink-850 text-ink-400 border-ink-700',
    sky: 'bg-sky-950 text-sky-200 border-sky-900',
    red: 'bg-red-950 text-red-300 border-red-900',
    amber: 'bg-amber-950 text-amber-300 border-amber-900',
    emerald: 'bg-emerald-950 text-emerald-300 border-emerald-900',
  };
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10.5px] font-semibold uppercase tracking-wide ${tones[tone]} ${className}`}
    >
      {children}
    </span>
  );
}

/** The one text input. Focus ring in Acuver blue; consistent border/placeholder treatment. */
type InputProps = React.ComponentPropsWithoutRef<'input'>;

export function Input({ className = '', ...rest }: InputProps) {
  return (
    <input
      className={`rounded-lg border border-ink-600 bg-ink-900 px-3 py-2 text-[13px] text-ink-100 outline-none transition-colors placeholder:text-ink-400 focus:border-sky-600 focus:ring-2 focus:ring-sky-600/20 ${className}`}
      {...rest}
    />
  );
}

/** The multi-line counterpart of {@link Input}, same field treatment. */
type TextareaProps = React.ComponentPropsWithoutRef<'textarea'>;

export function Textarea({ className = '', ...rest }: TextareaProps) {
  return (
    <textarea
      className={`rounded-lg border border-ink-600 bg-ink-900 px-3 py-2 text-[13px] text-ink-100 outline-none transition-colors placeholder:text-ink-400 focus:border-sky-600 focus:ring-2 focus:ring-sky-600/20 ${className}`}
      {...rest}
    />
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
