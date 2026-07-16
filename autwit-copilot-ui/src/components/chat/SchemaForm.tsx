import { useState } from 'react';

/**
 * The subset of JSON Schema the palette generates forms from.
 *
 * SKILL_CONTRACT §2: "input_schema is JSON Schema and drives the ⌘K palette's form
 * generation — keep it accurate and keep enums populated."
 */
type JsonSchema = {
  type?: string;
  required?: string[];
  properties?: Record<string, PropertySchema>;
};

type PropertySchema = {
  type?: string;
  enum?: string[];
  default?: unknown;
  description?: string;
  title?: string;
};

/**
 * Generates a form from a skill's input_schema.
 *
 * Deliberately supports only what the contract's schemas actually use — string (with
 * enum → select), integer/number, boolean. An unrecognised type renders a plain text
 * field and says so, rather than silently omitting the input: a field the tester cannot
 * see is a field they cannot set, and they would have no way of knowing why the skill
 * rejected them.
 *
 * Validation is left to the API. copilot-api validates against input_schema before
 * sending, and the orchestrator validates again and returns input_schema_violation
 * (SKILL_CONTRACT §4). Re-implementing JSON Schema here would add a third opinion that
 * can disagree with both.
 */
export function SchemaForm({
  schema,
  value,
  onChange,
}: {
  schema: JsonSchema;
  value: Record<string, unknown>;
  onChange: (next: Record<string, unknown>) => void;
}) {
  const properties = schema.properties ?? {};
  const required = new Set(schema.required ?? []);
  const names = Object.keys(properties);

  if (names.length === 0) {
    return <p className="text-[12px] text-ink-400 italic">This skill takes no input.</p>;
  }

  return (
    <div className="space-y-2.5">
      {names.map((name) => {
        const property = properties[name]!;
        return (
          <label key={name} className="block">
            <span className="mb-1 flex items-baseline gap-1.5">
              <span className="font-mono text-[12px] text-ink-100">{property.title ?? name}</span>
              {required.has(name) && <span className="text-[10px] text-red-400">required</span>}
              {property.type && (
                <span className="ml-auto font-mono text-[10px] text-ink-400">{property.type}</span>
              )}
            </span>

            <Field
              name={name}
              property={property}
              value={value[name]}
              onChange={(next) => onChange({ ...value, [name]: next })}
            />

            {property.description && (
              <span className="mt-1 block text-[11px] text-ink-400">{property.description}</span>
            )}
          </label>
        );
      })}
    </div>
  );
}

const INPUT_CLASS =
  'w-full rounded border border-ink-700 bg-ink-950 px-2 py-1 font-mono text-[12px] text-ink-100 outline-none focus:border-sky-700';

function Field({
  name,
  property,
  value,
  onChange,
}: {
  name: string;
  property: PropertySchema;
  value: unknown;
  onChange: (next: unknown) => void;
}) {
  // enum -> select. This is why §2 insists the enums stay populated: it is the
  // difference between picking 'order_flow' and guessing at it.
  if (property.enum) {
    return (
      <select
        className={INPUT_CLASS}
        value={String(value ?? property.default ?? '')}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">—</option>
        {property.enum.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    );
  }

  if (property.type === 'boolean') {
    return (
      <input
        type="checkbox"
        checked={Boolean(value ?? property.default ?? false)}
        onChange={(e) => onChange(e.target.checked)}
        className="size-4 accent-sky-600"
      />
    );
  }

  if (property.type === 'integer' || property.type === 'number') {
    return (
      <input
        type="number"
        className={INPUT_CLASS}
        value={String(value ?? property.default ?? '')}
        step={property.type === 'integer' ? 1 : 'any'}
        onChange={(e) => {
          const raw = e.target.value;
          if (raw === '') return onChange(undefined);
          const parsed = property.type === 'integer' ? parseInt(raw, 10) : parseFloat(raw);
          onChange(Number.isNaN(parsed) ? undefined : parsed);
        }}
      />
    );
  }

  const unsupported = property.type !== undefined && property.type !== 'string';

  return (
    <>
      <input
        type="text"
        className={INPUT_CLASS}
        placeholder={name}
        value={String(value ?? property.default ?? '')}
        onChange={(e) => onChange(e.target.value || undefined)}
      />
      {unsupported && (
        // Say so rather than hide it: a field the tester cannot see is one they cannot
        // set, and they would not know why the skill rejected them.
        <span className="mt-1 block text-[11px] text-amber-300/80">
          Type "{property.type}" has no editor here — this value is sent as text.
        </span>
      )}
    </>
  );
}

/** Drops keys the tester left empty, so the API sees an absent field rather than "". */
export function pruneEmpty(value: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(value).filter(([, v]) => v !== undefined && v !== '' && v !== null),
  );
}

/** @return the required fields that are still empty, for the submit button's gate. */
export function missingRequired(schema: JsonSchema, value: Record<string, unknown>): string[] {
  return (schema.required ?? []).filter((name) => {
    const v = value[name];
    return v === undefined || v === '' || v === null;
  });
}

export function useSchemaForm(initial: Record<string, unknown> = {}) {
  return useState<Record<string, unknown>>(initial);
}

export type { JsonSchema };
