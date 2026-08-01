import { useRef, useState } from 'react';
import type { SourceDocumentView } from '../../api/client';
import {
  useAddDocument,
  useDeleteDocument,
  useDocuments,
  useSetSelected,
  useUploadDocument,
} from '../../hooks/usePlanning';
import { Card, EmptyState, Mono, Spinner } from '../../components/ui';

/** Step 1 — add the source material the plan is based on. */
export function InputsStage({ projectId, onNext }: { projectId: string; onNext: () => void }) {
  const { data: docs, isLoading } = useDocuments(projectId);
  const add = useAddDocument(projectId);
  const upload = useUploadDocument(projectId);
  const setSelected = useSetSelected(projectId);
  const remove = useDeleteDocument(projectId);
  const fileRef = useRef<HTMLInputElement>(null);
  const [paste, setPaste] = useState('');
  const [error, setError] = useState<string>();

  async function onFiles(files: FileList | null) {
    if (!files) return;
    setError(undefined);
    // Every file is uploaded as-is and parsed server-side (Tika) — PDF/DOCX/XLSX and text alike.
    for (const f of Array.from(files)) {
      try {
        await upload.mutateAsync(f);
      } catch (e) {
        setError((e as { detail?: string }).detail ?? `Could not add ${f.name}.`);
      }
    }
    if (fileRef.current) fileRef.current.value = '';
  }

  async function onPaste() {
    if (!paste.trim()) return;
    setError(undefined);
    try {
      await add.mutateAsync({ source_type: 'paste', title: 'Pasted text', text: paste });
      setPaste('');
    } catch (e) {
      setError((e as { detail?: string }).detail ?? 'Could not add the pasted text.');
    }
  }

  return (
    <div className="mx-auto max-w-3xl">
      <h2 className="text-lg font-semibold">What should we base the test plan on?</h2>
      <p className="mt-1 mb-5 text-[13px] text-ink-400">
        Upload design/requirement docs or paste content. We'll combine these with anything
        relevant we find in Jira and Confluence next.
      </p>

      <Card className="mb-4">
        <h3 className="text-[13px] font-semibold">Design &amp; requirement docs</h3>
        <p className="mb-3 text-[11px] text-ink-400">
          PDF, Word, Excel, Markdown or plain text · parsed on the server
        </p>
        <button
          onClick={() => fileRef.current?.click()}
          disabled={upload.isPending}
          className="w-full rounded-lg border border-dashed border-ink-600 py-6 text-center text-[13px] text-ink-300 hover:border-sky-700 hover:bg-sky-700/5 disabled:opacity-50"
        >
          <div className="text-lg">⇪</div>
          {upload.isPending ? 'Uploading…' : 'Drop files or click to browse'}
        </button>
        <input
          ref={fileRef}
          type="file"
          multiple
          accept=".pdf,.doc,.docx,.xls,.xlsx,.md,.markdown,.txt,.csv,.html,.htm,.json,.xml,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/*"
          className="hidden"
          onChange={(e) => onFiles(e.target.files)}
        />

        <div className="mt-4">
          <textarea
            value={paste}
            onChange={(e) => setPaste(e.target.value)}
            placeholder="…or paste requirement text here"
            rows={3}
            className="w-full rounded border border-ink-700 bg-ink-950 px-2 py-1.5 text-[12px] outline-none focus:border-sky-700"
          />
          <div className="mt-1.5 flex justify-end">
            <button
              onClick={onPaste}
              disabled={!paste.trim() || add.isPending}
              className="rounded border border-ink-700 px-2 py-1 text-[11px] hover:border-ink-600 disabled:opacity-40"
            >
              Add pasted text
            </button>
          </div>
        </div>

        {error && <p className="mt-2 text-[11px] text-red-300">{error}</p>}
      </Card>

      <h3 className="mb-2 text-[12px] font-semibold uppercase tracking-wide text-ink-400">
        Corpus {docs && `(${docs.length})`}
      </h3>
      {isLoading && <Spinner />}
      {docs && docs.length === 0 && <EmptyState>No documents yet.</EmptyState>}
      <ul className="space-y-1.5">
        {docs?.map((d: SourceDocumentView) => (
          <li key={d.document_id}>
            <div className="flex items-center gap-2.5 rounded-lg border border-ink-700 bg-ink-900 px-3 py-2">
              <input
                type="checkbox"
                checked={d.selected}
                onChange={(e) => setSelected.mutate({ documentId: d.document_id, selected: e.target.checked })}
                className="accent-sky-600"
              />
              <span className="flex-1 truncate text-[13px]">{d.title}</span>
              <Mono className="rounded border border-ink-700 bg-ink-850 px-1.5 py-0.5 text-ink-400">
                {d.source_type}
              </Mono>
              <span className="text-[11px] tabular-nums text-ink-500">{d.text_length} ch</span>
              <button
                onClick={() => remove.mutate(d.document_id)}
                className="text-ink-500 hover:text-red-400"
                title="Remove"
              >
                ✕
              </button>
            </div>
          </li>
        ))}
      </ul>

      <div className="mt-6 flex justify-end">
        <button
          onClick={onNext}
          className="rounded bg-sky-700 px-4 py-2 text-[13px] font-medium text-white hover:bg-sky-600"
        >
          Continue to Jira &amp; Confluence →
        </button>
      </div>
    </div>
  );
}
