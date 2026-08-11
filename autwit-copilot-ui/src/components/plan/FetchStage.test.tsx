import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { FetchStage } from './FetchStage';

/**
 * Step 2's two changed behaviours: searches accumulate instead of replacing, and references
 * can be typed or pasted instead of only picked from search. Both existed as gaps a tester
 * hit immediately — one search rarely covers a feature, and a ticket you already know the
 * key of had to be found by keyword first.
 */

const { mockJira, mockConfluence, mockFetch, fetchMutate } = vi.hoisted(() => ({
  mockJira: vi.fn(),
  mockConfluence: vi.fn(),
  mockFetch: vi.fn(),
  fetchMutate: vi.fn(),
}));

vi.mock('../../hooks/usePlanning', () => ({
  useJiraSearch: (_p: string, q: string) => mockJira(q),
  useConfluenceSearch: (_p: string, q: string) => mockConfluence(q),
  useFetchContext: () => mockFetch(),
}));

const candidate = (kind: string, ref: string, title: string) => ({
  ref,
  title,
  kind,
  status: null,
  meta: null,
  url: null,
});

/** Search results keyed by query, so a second search returns a different batch. */
function stubSearches(byQuery: Record<string, { jira?: unknown[]; confluence?: unknown[] }>) {
  mockJira.mockImplementation((q: string) => ({
    data: byQuery[q]?.jira ?? [],
    isSuccess: true,
    isFetching: false,
  }));
  mockConfluence.mockImplementation((q: string) => ({
    data: byQuery[q]?.confluence ?? [],
    isSuccess: true,
    isFetching: false,
  }));
}

function renderStage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <FetchStage projectId="p1" onBack={() => {}} onNext={() => {}} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  fetchMutate.mockReset();
  mockFetch.mockReturnValue({
    mutate: fetchMutate,
    data: undefined,
    isPending: false,
    isSuccess: false,
  });
});

describe('searching more than once', () => {
  test('a second search adds to the first rather than replacing it', async () => {
    const user = userEvent.setup();
    stubSearches({
      cancel: { jira: [candidate('jira', 'CAN-1201', 'Partial cancellation')] },
      refund: { jira: [candidate('jira', 'PAY-2481', 'Refund on return')] },
    });
    renderStage();

    await user.type(screen.getByPlaceholderText('Search issues & pages'), 'cancel');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('CAN-1201')).toBeInTheDocument();

    await user.clear(screen.getByPlaceholderText('Search issues & pages'));
    await user.type(screen.getByPlaceholderText('Search issues & pages'), 'refund');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    // The first search's hit is still there — this is the regression that mattered.
    expect(await screen.findByText('PAY-2481')).toBeInTheDocument();
    expect(screen.getByText('CAN-1201')).toBeInTheDocument();
    expect(screen.getByText(/Searched:/)).toHaveTextContent('“cancel”');
    expect(screen.getByText(/Searched:/)).toHaveTextContent('“refund”');
  });

  test('both searches’ results are selected and fetched together', async () => {
    const user = userEvent.setup();
    stubSearches({
      cancel: { jira: [candidate('jira', 'CAN-1201', 'Partial cancellation')] },
      design: { confluence: [candidate('confluence', '123456789', 'Cancellation design')] },
    });
    renderStage();

    const box = screen.getByPlaceholderText('Search issues & pages');
    await user.type(box, 'cancel');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await screen.findByText('CAN-1201');
    await user.clear(box);
    await user.type(box, 'design');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await screen.findByText('123456789');

    await user.click(screen.getByRole('button', { name: /Fetch selected context \(2\)/ }));

    expect(fetchMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        jira_keys: ['CAN-1201'],
        confluence_page_ids: ['123456789'],
      }),
    );
  });
});

describe('adding references directly', () => {
  test('keys and links are split on commas, spaces and newlines and sent as refs', async () => {
    const user = userEvent.setup();
    stubSearches({});
    renderStage();

    await user.type(
      screen.getByPlaceholderText(/PAY-2481, CAN-1201/),
      'PAY-2481, CAN-1201\nhttps://acuver.atlassian.net/wiki/spaces/OES/pages/123456789/Design',
    );
    await user.click(screen.getByRole('button', { name: 'Add' }));

    // Sent verbatim: classification is the server's job, so the browser never has to know
    // which URL forms are supported.
    await user.click(screen.getByRole('button', { name: /Fetch selected context \(3\)/ }));
    expect(fetchMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        refs: [
          'PAY-2481',
          'CAN-1201',
          'https://acuver.atlassian.net/wiki/spaces/OES/pages/123456789/Design',
        ],
      }),
    );
  });

  test('an entry can be removed before fetching, and duplicates are not added twice', async () => {
    const user = userEvent.setup();
    stubSearches({});
    renderStage();

    const box = screen.getByPlaceholderText(/PAY-2481, CAN-1201/);
    await user.type(box, 'PAY-1 PAY-2');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    await user.type(box, 'PAY-2');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    expect(screen.getByRole('button', { name: /Fetch selected context \(2\)/ })).toBeInTheDocument();

    await user.click(screen.getByLabelText('Remove PAY-1'));
    await user.click(screen.getByRole('button', { name: /Fetch selected context \(1\)/ }));
    expect(fetchMutate).toHaveBeenCalledWith(expect.objectContaining({ refs: ['PAY-2'] }));
  });

  test('fetch stays disabled until something is selected or entered', () => {
    stubSearches({});
    renderStage();
    expect(screen.getByRole('button', { name: /Fetch selected context \(0\)/ })).toBeDisabled();
  });
});

describe('the fetch console', () => {
  test('a skipped reference is surfaced as a warning, not swallowed', () => {
    stubSearches({});
    mockFetch.mockReturnValue({
      mutate: fetchMutate,
      isPending: false,
      isSuccess: true,
      data: {
        documents: [],
        log: [
          {
            ts: '10:00:01',
            level: 'warn',
            source: 'input',
            ref: 'https://acuver.atlassian.net/wiki/x/AbCdEf',
            message: 'Skipped "https://acuver.atlassian.net/wiki/x/AbCdEf" — Confluence short links do not contain the page id',
          },
          { ts: '10:00:02', level: 'ok', source: 'jira', ref: 'PAY-1', message: 'Fetched PAY-1' },
        ],
      },
    });
    renderStage();

    expect(screen.getByText(/Confluence short links do not contain the page id/)).toBeInTheDocument();
    // The rest of the batch still landed — the point of not failing the whole request.
    expect(screen.getByText('Fetched PAY-1')).toBeInTheDocument();
  });
});
