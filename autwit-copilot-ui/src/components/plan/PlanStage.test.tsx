import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import type { TestPlanView } from '../../api/client';
import { PlanStage } from './PlanStage';

/**
 * Rendering guards for the v2 plan. The UI had no test framework at all before this, so a
 * dropped field or a plan shape that crashes the page was invisible until someone opened it
 * in a browser. These use representative fixtures rather than snapshots: a snapshot would go
 * red on every wording tweak without telling you which field stopped rendering.
 */

const { mockGeneration, mockPlan } = vi.hoisted(() => ({
  mockGeneration: vi.fn(),
  mockPlan: vi.fn(),
}));

vi.mock('../../hooks/usePlanning', () => ({
  useGeneration: () => mockGeneration(),
  useLatestTestPlan: () => mockPlan(),
}));

function renderPlan(plan: TestPlanView | null, generationStatus = 'succeeded') {
  mockGeneration.mockReturnValue({ data: { status: generationStatus } });
  mockPlan.mockReturnValue({ data: plan });
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <PlanStage projectId="p1" onBack={() => {}} onNext={() => {}} />
    </QueryClientProvider>,
  );
}

const richPlan = (overrides: Partial<TestPlanView> = {}): TestPlanView =>
  ({
    test_plan_id: 'tp1',
    generation_id: 'g1',
    plan_version: 2,
    overview: 'Partial cancellation lets a member cancel eligible lines.',
    scope: { in_scope: ['Partial line cancellation'], out_of_scope: ['Returns after invoice'] },
    architecture_context: {
      summary: 'Cancellation is an overlay on the order lifecycle.',
      participants: [{ name: 'OMS', role: 'owns order state' }],
      lifecycle_phases: [{ name: 'Order capture', description: 'Order created' }],
      feature_injection_points: [
        { phase: 'Scheduling', description: 'Rejected once released', sources: ['CAN-1201'] },
      ],
    },
    requirements: [
      {
        id: 'REQ-01',
        statement: 'A line in CREATED is eligible for cancellation.',
        category: 'functional',
        sources: ['CAN-1201'],
        evidence: null,
        lifecycle_phase: 'Order capture',
      },
    ],
    test_data_requirements: [
      {
        id: 'DATA-01',
        name: 'Multi-line order',
        description: 'An order with two eligible lines',
        attributes: ['two or more lines'],
        source_of_truth: null,
      },
    ],
    capabilities: [
      {
        name: 'Eligibility',
        description: 'Which lines may be cancelled, and when.',
        test_cases: [
          {
            scenario_key: 'TC-01',
            seq: 1,
            capability: 'Eligibility',
            title: 'Cancel one eligible line',
            priority: 'High',
            objective: 'Proves an eligible line cancels.',
            lifecycle_phase: 'Order capture',
            sources: ['CAN-1201'],
            requirement_ids: ['REQ-01'],
            preconditions: ['An order in CREATED with two lines'],
            steps: ['Cancel line 1'],
            expected_results: ['Line 1 status is CANCELLED'],
            test_data_requirements: ['DATA-01'],
            automation_mapping: { type: 'api', target: 'POST /cancel', notes: 'AUTWIT facade' },
            source: 'CAN-1201',
          },
        ],
      },
      {
        name: 'Financial effects',
        description: 'Totals after cancellation.',
        test_cases: [
          {
            scenario_key: 'TC-02',
            seq: 2,
            capability: 'Financial effects',
            title: 'Totals recalculate',
            priority: 'Medium',
            objective: 'Proves totals reconcile.',
            lifecycle_phase: null,
            sources: ['CAN-1201'],
            requirement_ids: [],
            preconditions: [],
            steps: ['Read the totals'],
            expected_results: ['Total equals the remaining lines'],
            test_data_requirements: [],
            automation_mapping: null,
            source: 'CAN-1201',
          },
        ],
      },
    ],
    execution_strategy: 'Run eligibility before financial effects.',
    risks: [{ title: 'Reversal timing', detail: 'Async', mitigation: 'Assert on the event' }],
    gaps: [{ title: 'Window not stated', detail: 'No cut-off given', blocks_testing: false }],
    provenance: { sources: ['CAN-1201'], doc_version: null },
    scenarios: [],
    created_at: '2026-08-11T00:00:00Z',
    ...overrides,
  }) as TestPlanView;

describe('the v2 plan', () => {
  test('groups test cases under their capability headings', () => {
    renderPlan(richPlan());

    expect(screen.getByText('Eligibility')).toBeInTheDocument();
    expect(screen.getByText('Financial effects')).toBeInTheDocument();
    expect(screen.getByText('Which lines may be cancelled, and when.')).toBeInTheDocument();
    // Case counts per group, so a big plan stays scannable while collapsed.
    expect(screen.getAllByText('1 case')).toHaveLength(2);
    expect(screen.getByText('2 test cases · 2 capabilities')).toBeInTheDocument();
  });

  test('shows each case with the detail a tester needs to execute it', () => {
    renderPlan(richPlan());

    // Scoped to the case: ids like DATA-01 and REQ-01 legitimately appear both in the
    // plan-level summary sections and inside the cases that reference them.
    const first = screen.getByText('Cancel one eligible line').closest('details')!;

    expect(within(first).getByText('TC-01')).toBeInTheDocument();
    expect(within(first).getByText('Proves an eligible line cancels.')).toBeInTheDocument();
    expect(within(first).getByText('An order in CREATED with two lines')).toBeInTheDocument();
    expect(within(first).getByText('Cancel line 1')).toBeInTheDocument();
    expect(within(first).getByText('Line 1 status is CANCELLED')).toBeInTheDocument();
    expect(within(first).getByText('DATA-01')).toBeInTheDocument();
    expect(within(first).getByText('POST /cancel')).toBeInTheDocument();
    expect(within(first).getByText(/Covers REQ-01/)).toBeInTheDocument();
    // Priority renders as a badge, not bare coloured text.
    expect(within(first).getByText('High')).toBeInTheDocument();
  });

  test('renders the lifecycle phase on a case that has one', () => {
    renderPlan(richPlan());
    // Present as the case's phase chip and in the architecture lifecycle strip.
    expect(screen.getAllByText('Order capture').length).toBeGreaterThan(0);
  });

  test('renders the summary sections: overview, scope, requirements, data, risks and gaps', () => {
    renderPlan(richPlan());

    expect(screen.getByText(/Partial cancellation lets a member/)).toBeInTheDocument();
    expect(screen.getByText('Partial line cancellation')).toBeInTheDocument();
    expect(screen.getByText('Returns after invoice')).toBeInTheDocument();
    expect(screen.getByText('REQ-01')).toBeInTheDocument();
    expect(screen.getByText(/A line in CREATED is eligible/)).toBeInTheDocument();
    expect(screen.getByText('Multi-line order')).toBeInTheDocument();
    expect(screen.getByText('Run eligibility before financial effects.')).toBeInTheDocument();
    // Risks and gaps are distinct concepts and get their own sections.
    expect(screen.getByText('Risks')).toBeInTheDocument();
    expect(screen.getByText('Open gaps')).toBeInTheDocument();
    expect(screen.getByText('Reversal timing')).toBeInTheDocument();
    expect(screen.getByText('Window not stated')).toBeInTheDocument();
  });
});

describe('optional evidence-dependent fields', () => {
  test('omits the architecture section when no architecture material was supplied', () => {
    renderPlan(richPlan({ architecture_context: null }));

    expect(screen.queryByText('Architecture context')).not.toBeInTheDocument();
    // The rest of the plan still renders.
    expect(screen.getByText('Eligibility')).toBeInTheDocument();
  });

  test('omits the automation line on a case with no automation target', () => {
    renderPlan(richPlan());

    const secondCase = screen.getByText('Totals recalculate').closest('details')!;
    expect(within(secondCase).queryByText('Automation')).not.toBeInTheDocument();
    // ...while the case that does have one shows it.
    const firstCase = screen.getByText('Cancel one eligible line').closest('details')!;
    expect(within(firstCase).getByText('Automation')).toBeInTheDocument();
  });

  test('marks a gap that blocks testing', () => {
    renderPlan(richPlan({ gaps: [{ title: 'Missing rule', detail: 'x', blocks_testing: true }] }));
    expect(screen.getByText('Blocks testing')).toBeInTheDocument();
  });
});

describe('backwards compatibility', () => {
  test('an old shallow plan renders the flat table instead of crashing', () => {
    // Exactly what a plan_version 1 row reads back as: no capabilities, no per-case detail,
    // prose scope folded into in_scope.
    const legacy = {
      test_plan_id: 'tp0',
      generation_id: 'g0',
      plan_version: 1,
      overview: 'Legacy overview',
      scope: { in_scope: ['Covers retry. Excludes refunds.'], out_of_scope: [] },
      architecture_context: null,
      requirements: [],
      test_data_requirements: [],
      capabilities: [],
      execution_strategy: null,
      risks: [],
      gaps: [],
      provenance: { sources: ['PAY-1'] },
      scenarios: [
        {
          scenario_key: 'TC-01',
          seq: 1,
          title: 'Legacy scenario',
          priority: 'High',
          source: 'PAY-1',
        },
      ],
      created_at: '2026-07-01T00:00:00Z',
    } as unknown as TestPlanView;

    renderPlan(legacy);

    expect(screen.getByText('Legacy overview')).toBeInTheDocument();
    expect(screen.getByText('Test scenarios')).toBeInTheDocument();
    expect(screen.getByText('Legacy scenario')).toBeInTheDocument();
    expect(screen.getByText('TC-01')).toBeInTheDocument();
    // No capability grouping to show, so the grouped heading must not appear.
    expect(screen.queryByText('Test cases by capability')).not.toBeInTheDocument();
  });

  test('a plan whose optional arrays are missing entirely does not crash', () => {
    // Defensive: an older persisted row read through a partial adapter.
    const sparse = {
      test_plan_id: 'tp2',
      generation_id: 'g2',
      plan_version: 1,
      overview: 'Just an overview',
      scenarios: [],
    } as unknown as TestPlanView;

    renderPlan(sparse);
    expect(screen.getByText('Just an overview')).toBeInTheDocument();
  });
});

describe('generation states', () => {
  test('shows progress while the generation is still running', () => {
    mockGeneration.mockReturnValue({ data: { status: 'running' } });
    mockPlan.mockReturnValue({ data: richPlan() });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <PlanStage projectId="p1" generationId="g1" onBack={() => {}} onNext={() => {}} />
      </QueryClientProvider>,
    );
    expect(screen.getByText(/Generating the test plan/)).toBeInTheDocument();
  });

  test('surfaces the failure detail when the generation failed', () => {
    mockGeneration.mockReturnValue({
      data: { status: 'failed', error: { detail: 'upstream unavailable' } },
    });
    mockPlan.mockReturnValue({ data: null });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <PlanStage projectId="p1" generationId="g1" onBack={() => {}} onNext={() => {}} />
      </QueryClientProvider>,
    );
    expect(screen.getByText(/upstream unavailable/)).toBeInTheDocument();
  });
});
