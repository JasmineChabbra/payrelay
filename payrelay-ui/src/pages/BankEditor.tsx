import { useEffect, useMemo, useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError, type AuthType, type BankAuth, type BankMeta, type BankSpec, type BankView, type FieldRule } from '../api'
import { authLabel, dateTime } from '../format'
import { Button, ErrorNotice, Loading, Panel } from '../components/ui'

interface Draft {
  displayName: string
  enabled: boolean
  spec: BankSpec
}

const toDraft = (b: BankView): Draft => ({ displayName: b.displayName, enabled: b.enabled, spec: b.spec })

/** Waits until the value stops changing, so the preview isn't requested on every keystroke. */
function useDebounced<T>(value: T, ms: number) {
  const [v, setV] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return v
}

export function BankEditor() {
  const { code = '' } = useParams()
  const qc = useQueryClient()
  const bank = useQuery({ queryKey: ['bank', code], queryFn: () => api.bank(code) })
  const meta = useQuery({ queryKey: ['bankMeta'], queryFn: api.bankMeta, staleTime: Infinity })
  const history = useQuery({ queryKey: ['bankHistory', code], queryFn: () => api.bankHistory(code) })

  const [draft, setDraft] = useState<Draft | null>(null)
  const [saveErrors, setSaveErrors] = useState<string[]>([])
  const [conflict, setConflict] = useState<string | null>(null)
  const [savedMessage, setSavedMessage] = useState<string | null>(null)

  // Load the server copy into the editor the first time it arrives.
  useEffect(() => {
    if (bank.data && draft === null) setDraft(toDraft(bank.data))
  }, [bank.data, draft])

  const dirty = useMemo(
    () => !!bank.data && !!draft && JSON.stringify(draft) !== JSON.stringify(toDraft(bank.data)),
    [bank.data, draft],
  )

  const specJson = draft ? JSON.stringify(draft.spec) : ''
  const debouncedSpec = useDebounced(specJson, 400)
  const preview = useQuery({
    queryKey: ['bankPreview', code, debouncedSpec],
    queryFn: () => api.previewBank(code, JSON.parse(debouncedSpec)),
    enabled: debouncedSpec !== '',
    placeholderData: keepPreviousData,
  })

  const save = useMutation({
    mutationFn: () => api.saveBank(code, { ...draft!, version: bank.data!.version }),
    onSuccess: (saved) => {
      qc.setQueryData(['bank', code], saved)
      setDraft(toDraft(saved))
      setSaveErrors([])
      setConflict(null)
      setSavedMessage(`Saved as version ${saved.version}. New transfers to ${saved.displayName} use it straight away.`)
      qc.invalidateQueries({ queryKey: ['banks'] })
      qc.invalidateQueries({ queryKey: ['bankHistory', code] })
    },
    onError: (e) => {
      setSavedMessage(null)
      if (e instanceof ApiError && e.status === 409) {
        setConflict(e.message)
        setSaveErrors([])
      } else if (e instanceof ApiError) {
        setSaveErrors(e.details.length ? e.details : [e.message])
      } else {
        setSaveErrors([(e as Error).message])
      }
    },
  })

  const reloadLatest = async () => {
    const latest = await bank.refetch()
    if (latest.data) setDraft(toDraft(latest.data))
    setConflict(null)
    setSaveErrors([])
  }

  if (bank.isLoading || !draft) return bank.error ? <ErrorNotice error={bank.error} /> : <Loading />

  const setSpec = (patch: Partial<BankSpec>) => {
    setSavedMessage(null)
    setDraft((d) => d && { ...d, spec: { ...d.spec, ...patch } })
  }
  const spec = draft.spec

  return (
    <>
      <Link to="/banks" className="text-sm text-muted hover:text-ink">Back to banks</Link>

      <div className="mt-3 mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{bank.data!.displayName}</h1>
          <p className="mt-1 text-sm text-muted">
            <span className="font-mono">{code}</span>, version {bank.data!.version}, last changed {dateTime(bank.data!.updatedAt)}
          </p>
        </div>
        <div className="flex items-center gap-3">
          {dirty && <span className="text-sm text-wait">Unsaved changes</span>}
          <Button variant="secondary" disabled={!dirty || save.isPending} onClick={() => { setDraft(toDraft(bank.data!)); setSaveErrors([]) }}>
            Discard
          </Button>
          <Button disabled={!dirty || save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? 'Saving…' : 'Save new version'}
          </Button>
        </div>
      </div>

      {savedMessage && <p role="status" className="mb-4 rounded-lg border border-ok/30 bg-ok-soft px-4 py-3 text-sm text-ok">{savedMessage}</p>}
      {conflict && (
        <div role="alert" className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-wait/30 bg-wait-soft px-4 py-3 text-sm text-wait">
          <span>{conflict}</span>
          <Button variant="secondary" onClick={reloadLatest}>Load latest version</Button>
        </div>
      )}
      {saveErrors.length > 0 && <ProblemList title="Not saved. Fix these first:" problems={saveErrors} />}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_380px]">
        <div className="min-w-0 space-y-6">
          <Panel title="Connection">
            <div className="grid gap-4 px-5 py-4 sm:grid-cols-2">
              <TextField label="Display name" value={draft.displayName}
                onChange={(v) => { setSavedMessage(null); setDraft({ ...draft, displayName: v }) }} />
              <TextField label="Transfer endpoint path" mono value={spec.endpoint?.path ?? ''}
                onChange={(v) => setSpec({ endpoint: { path: v } })} />
              <label className="flex items-center gap-3 text-sm sm:col-span-2">
                <input type="checkbox" checked={draft.enabled} className="size-4 accent-brand"
                  onChange={(e) => { setSavedMessage(null); setDraft({ ...draft, enabled: e.target.checked }) }} />
                <span>
                  Accept new transfers
                  <span className="block text-xs text-muted">When off, transfers to this bank wait and retry later instead of failing.</span>
                </span>
              </label>
            </div>
          </Panel>

          <AuthEditor code={code} auth={spec.auth} onChange={(auth) => setSpec({ auth })} />

          <Panel title="Request body">
            <p className="px-5 pt-3 text-sm text-muted">Each row builds one field of the JSON body. Use dots for nesting, e.g. <code className="font-mono text-xs">beneficiary.account.number</code>.</p>
            <RuleTable rules={spec.request.body} meta={meta.data}
              onChange={(body) => setSpec({ request: { ...spec.request, body } })} />
          </Panel>

          <Panel title="Request headers">
            <p className="px-5 pt-3 text-sm text-muted">Authentication headers are added automatically. Map the idempotency key here if this bank expects it in a header.</p>
            <RuleTable rules={spec.request.headers} meta={meta.data}
              onChange={(headers) => setSpec({ request: { ...spec.request, headers } })} />
          </Panel>

          <Panel title="Reading the response">
            <div className="grid gap-4 px-5 py-4 sm:grid-cols-2">
              <TextField label="Status field" mono value={spec.response.statusPath}
                onChange={(v) => setSpec({ response: { ...spec.response, statusPath: v } })} />
              <TextField label="Bank reference field" mono value={spec.response.referencePath}
                onChange={(v) => setSpec({ response: { ...spec.response, referencePath: v } })} />
              <ListField label="Status values meaning paid" value={spec.response.successValues}
                onChange={(v) => setSpec({ response: { ...spec.response, successValues: v } })} />
              <ListField label="Status values meaning rejected" value={spec.response.rejectedValues ?? []}
                onChange={(v) => setSpec({ response: { ...spec.response, rejectedValues: v } })} />
              <TextField label="Rejection reason field" mono value={spec.response.reasonPath ?? ''}
                onChange={(v) => setSpec({ response: { ...spec.response, reasonPath: v } })} />
              <p className="self-end text-xs text-muted">Any other status is treated as "not final yet" and retried with the same idempotency key.</p>
            </div>
          </Panel>

          <LookupsEditor lookups={spec.lookups ?? {}} onChange={(lookups) => setSpec({ lookups })} />
        </div>

        <div className="space-y-6 lg:sticky lg:top-6 lg:self-start">
          <Panel title="What the bank receives">
            <div className="px-5 py-4">
              <p className="mb-3 text-xs text-muted">
                Built live from your edits, using a sample payment. Secrets and tokens are shown as placeholders.
              </p>
              {preview.error && <ErrorNotice error={preview.error} />}
              {preview.data && preview.data.errors.length > 0 && <ProblemList title="Problems" problems={preview.data.errors} compact />}
              {preview.data && preview.data.body != null && (
                <pre className={`max-h-[28rem] overflow-auto rounded-md border border-line bg-canvas p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-all ${preview.isFetching ? 'opacity-60' : ''}`}>
                  {`${preview.data.method} ${preview.data.path ?? ''}\n`}
                  {Object.entries(preview.data.headers).map(([k, v]) => `${k}: ${v}\n`).join('')}
                  {'\n'}
                  {JSON.stringify(preview.data.body, null, 2)}
                </pre>
              )}
              {preview.data && (
                <details className="mt-3 text-xs">
                  <summary className="cursor-pointer text-muted">Sample payment used</summary>
                  <pre className="mt-2 overflow-auto rounded-md border border-line bg-panel p-3 font-mono whitespace-pre-wrap break-all">
                    {JSON.stringify(preview.data.sample, null, 2)}
                  </pre>
                </details>
              )}
            </div>
          </Panel>

          <Panel title="Version history">
            {history.data && (
              <ul className="divide-y divide-line text-sm">
                {history.data.map((h) => (
                  <li key={h.version} className="flex items-center justify-between gap-3 px-5 py-2.5">
                    <div>
                      <p className="font-medium">Version {h.version}</p>
                      <p className="text-xs text-muted">{dateTime(h.changedAt)}</p>
                    </div>
                    {h.version === bank.data!.version ? (
                      <span className="text-xs text-muted">Current</span>
                    ) : (
                      <Button variant="secondary" onClick={() => {
                        setSavedMessage(null)
                        setDraft({ displayName: h.displayName, enabled: h.enabled, spec: h.spec })
                      }}>
                        Load into editor
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            <p className="border-t border-line px-5 py-3 text-xs text-muted">
              Loading an old version doesn't change anything until you save it. Every bank call records the version it used.
            </p>
          </Panel>
        </div>
      </div>
    </>
  )
}

// ---------------------------------------------------------------- pieces

function ProblemList({ title, problems, compact }: { title: string; problems: string[]; compact?: boolean }) {
  return (
    <div role="alert" className={`rounded-lg border border-bad/30 bg-bad-soft text-sm text-bad ${compact ? 'mb-3 px-3 py-2' : 'mb-4 px-4 py-3'}`}>
      <p className="font-medium">{title}</p>
      <ul className="mt-1 list-disc space-y-0.5 pl-5">
        {problems.map((p) => <li key={p}>{p}</li>)}
      </ul>
    </div>
  )
}

const inputClass = 'w-full rounded-md border border-line bg-panel px-2.5 py-1.5 text-sm'

function TextField({ label, value, onChange, mono, placeholder, hint }: {
  label: string; value: string; onChange: (v: string) => void; mono?: boolean; placeholder?: string; hint?: string
}) {
  return (
    <label className="block text-xs text-muted">
      {label}
      <input value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)}
        className={`mt-1 ${inputClass} text-ink ${mono ? 'font-mono text-xs' : ''}`} />
      {hint && <span className="mt-1 block">{hint}</span>}
    </label>
  )
}

/** A comma-separated list, committed when the field loses focus so typing a comma isn't swallowed. */
function ListField({ label, value, onChange }: { label: string; value: string[]; onChange: (v: string[]) => void }) {
  const [text, setText] = useState(value.join(', '))
  useEffect(() => setText(value.join(', ')), [value])
  return (
    <label className="block text-xs text-muted">
      {label}
      <input value={text} onChange={(e) => setText(e.target.value)}
        onBlur={() => onChange(text.split(',').map((s) => s.trim()).filter(Boolean))}
        className={`mt-1 ${inputClass} font-mono text-xs text-ink`} placeholder="e.g. SUCCESS, PROCESSED" />
    </label>
  )
}

function AuthEditor({ code, auth, onChange }: { code: string; auth: BankAuth; onChange: (a: BankAuth) => void }) {
  const [revoked, setRevoked] = useState<string | null>(null)
  const revoke = useMutation({
    mutationFn: api.revokeZenithTokens,
    onSuccess: (r) => setRevoked(`Revoked ${r.revoked} token${r.revoked === 1 ? '' : 's'}. The next transfer will be refused with 401; PayRelay will fetch a new token and retry in the same attempt. Send some traffic and open a Zenith payment to see it.`),
  })
  const set = (patch: Partial<BankAuth>) => onChange({ ...auth, ...patch })
  const types: AuthType[] = ['API_KEY', 'OAUTH2_CLIENT_CREDENTIALS', 'HMAC_SHA256']

  return (
    <Panel title="Authentication">
      <div className="grid gap-4 px-5 py-4 sm:grid-cols-2">
        <label className="block text-xs text-muted">
          Method
          <select value={auth.type} onChange={(e) => set({ type: e.target.value as AuthType })}
            className={`mt-1 ${inputClass} text-ink`}>
            {types.map((t) => <option key={t} value={t}>{authLabel[t]}</option>)}
          </select>
        </label>
        <TextField label="Secret name" mono value={auth.secretRef ?? ''} onChange={(v) => set({ secretRef: v })}
          hint="Resolved from the environment (payrelay.secrets.<name>). Secret values never appear here." />

        {auth.type === 'API_KEY' && (
          <>
            <TextField label="Header" mono value={auth.header ?? ''} onChange={(v) => set({ header: v })} placeholder="X-Api-Key" />
            <TextField label="Value prefix (optional)" mono value={auth.prefix ?? ''} onChange={(v) => set({ prefix: v })} placeholder="ApiKey " />
          </>
        )}
        {auth.type === 'OAUTH2_CLIENT_CREDENTIALS' && (
          <>
            <TextField label="Token endpoint path" mono value={auth.tokenPath ?? ''} onChange={(v) => set({ tokenPath: v })} />
            <TextField label="Client ID" mono value={auth.clientId ?? ''} onChange={(v) => set({ clientId: v })} />
            <TextField label="Scope (optional)" mono value={auth.scope ?? ''} onChange={(v) => set({ scope: v })} />
          </>
        )}
        {auth.type === 'HMAC_SHA256' && (
          <>
            <TextField label="Key ID" mono value={auth.keyId ?? ''} onChange={(v) => set({ keyId: v })} />
            <TextField label="Signature header" mono value={auth.signatureHeader ?? ''} onChange={(v) => set({ signatureHeader: v })} />
            <TextField label="Timestamp header" mono value={auth.timestampHeader ?? ''} onChange={(v) => set({ timestampHeader: v })} />
            <TextField label="Key ID header (optional)" mono value={auth.keyIdHeader ?? ''} onChange={(v) => set({ keyIdHeader: v })} />
            <p className="text-xs text-muted sm:col-span-2">
              The signature is HMAC-SHA256 over the timestamp, method, path and a SHA-256 hash of the exact body, so a changed
              body or an old, replayed request fails verification.
            </p>
          </>
        )}
      </div>
      {code === 'ZENITH' && auth.type === 'OAUTH2_CLIENT_CREDENTIALS' && (
        <div className="flex flex-wrap items-center gap-3 border-t border-line px-5 py-3">
          <Button variant="secondary" onClick={() => revoke.mutate()} disabled={revoke.isPending}>Simulate token expiry</Button>
          {revoked && <p className="flex-1 text-xs text-muted" role="status">{revoked}</p>}
          {revoke.error && <p className="text-xs text-bad" role="alert">{(revoke.error as Error).message}</p>}
        </div>
      )}
    </Panel>
  )
}

const FIXED = '__fixed__'

function RuleTable({ rules, meta, onChange }: { rules: FieldRule[]; meta?: BankMeta; onChange: (r: FieldRule[]) => void }) {
  const update = (i: number, patch: Partial<FieldRule>) => onChange(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const remove = (i: number) => onChange(rules.filter((_, j) => j !== i))
  const add = () => onChange([...rules, { target: '', source: 'amount' }])

  return (
    <div className="px-5 pt-3 pb-4">
      {rules.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[600px] table-fixed text-sm">
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="w-[24%] pr-2 pb-1.5 font-medium">Field</th>
                <th className="w-[22%] pr-2 pb-1.5 font-medium">Value from</th>
                <th className="w-[26%] pr-2 pb-1.5 font-medium">Transforms</th>
                <th className="w-[16%] pr-2 pb-1.5 font-medium">If empty, use</th>
                <th className="w-[6%] pr-1 pb-1.5 text-center font-medium" title="Required">Req.</th>
                <th className="w-[6%] pb-1.5"><span className="sr-only">Remove</span></th>
              </tr>
            </thead>
            <tbody>
              {rules.map((r, i) => (
                <RuleRow key={i} rule={r} meta={meta} onChange={(p) => update(i, p)} onRemove={() => remove(i)} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <Button variant="secondary" onClick={add}>Add field</Button>
        {meta && (
          <details className="text-xs text-muted">
            <summary className="cursor-pointer">Available transforms</summary>
            <dl className="mt-2 grid max-w-xl grid-cols-[auto_1fr] gap-x-3 gap-y-1">
              {meta.transforms.map((t) => (
                <div key={t.name} className="contents">
                  <dt className="font-mono text-ink">{t.name}{t.takesArgument ? ':…' : ''}</dt>
                  <dd>{t.description}</dd>
                </div>
              ))}
            </dl>
          </details>
        )}
      </div>
    </div>
  )
}

function RuleRow({ rule, meta, onChange, onRemove }: {
  rule: FieldRule; meta?: BankMeta; onChange: (p: Partial<FieldRule>) => void; onRemove: () => void
}) {
  const isFixed = rule.constant !== null && rule.constant !== undefined
  const transforms = (rule.transforms ?? []).join(', ')
  const [transformText, setTransformText] = useState(transforms)
  useEffect(() => setTransformText(transforms), [transforms])

  return (
    <tr className="align-top">
      <td className="py-1 pr-2">
        <input aria-label="Field name" value={rule.target} onChange={(e) => onChange({ target: e.target.value })}
          className={`${inputClass} font-mono text-xs`} placeholder="beneficiary.name" />
      </td>
      <td className="py-1 pr-2">
        <select aria-label="Value from" value={isFixed ? FIXED : rule.source ?? ''}
          onChange={(e) => e.target.value === FIXED
            ? onChange({ source: null, constant: '' })
            : onChange({ source: e.target.value, constant: null })}
          className={inputClass}>
          {(meta?.sources ?? []).map((s) => <option key={s.name} value={s.name} title={s.description}>{s.name}</option>)}
          <option value={FIXED}>Fixed value</option>
        </select>
        {isFixed && (
          <input aria-label="Fixed value" value={rule.constant ?? ''} onChange={(e) => onChange({ constant: e.target.value })}
            className={`mt-1 ${inputClass} font-mono text-xs`} placeholder="Fixed value" />
        )}
      </td>
      <td className="py-1 pr-2">
        <input aria-label="Transforms" value={transformText} onChange={(e) => setTransformText(e.target.value)}
          onBlur={() => onChange({ transforms: transformText.split(',').map((s) => s.trim()).filter(Boolean) })}
          className={`${inputClass} font-mono text-xs`} placeholder="none" />
      </td>
      <td className="py-1 pr-2">
        <input aria-label="Default value" value={rule.defaultValue ?? ''}
          onChange={(e) => onChange({ defaultValue: e.target.value === '' ? null : e.target.value })}
          className={`${inputClass} text-xs`} />
      </td>
      <td className="py-1 pr-1 text-center">
        <input type="checkbox" aria-label="Required" checked={rule.required !== false} className="mt-2 size-4 accent-brand"
          onChange={(e) => onChange({ required: e.target.checked ? null : false })} />
      </td>
      <td className="py-1">
        <button onClick={onRemove} aria-label={`Remove ${rule.target || 'field'}`} title="Remove"
          className="rounded-md px-2 py-1 text-lg leading-none text-muted hover:bg-bad-soft hover:text-bad">×</button>
      </td>
    </tr>
  )
}

function LookupsEditor({ lookups, onChange }: { lookups: Record<string, Record<string, string>>; onChange: (l: Record<string, Record<string, string>>) => void }) {
  const tables = Object.entries(lookups)
  const setTable = (name: string, rows: [string, string][]) => onChange({ ...lookups, [name]: Object.fromEntries(rows) })
  const renameTable = (from: string, to: string) => {
    const next: Record<string, Record<string, string>> = {}
    Object.entries(lookups).forEach(([k, v]) => { next[k === from ? to : k] = v })
    onChange(next)
  }
  const removeTable = (name: string) => {
    const next = { ...lookups }
    delete next[name]
    onChange(next)
  }
  const addTable = () => {
    let name = 'newTable'
    for (let i = 2; lookups[name]; i++) name = `newTable${i}`
    onChange({ ...lookups, [name]: { '*': '' } })
  }

  return (
    <Panel title="Lookup tables" action={<Button variant="secondary" onClick={addTable}>Add table</Button>}>
      <p className="px-5 pt-3 text-sm text-muted">
        Translate PayRelay values into this bank's codes, e.g. purpose "Loan disbursement" to "LNDSB".
        Use <code className="font-mono text-xs">LOOKUP:tableName</code> in a field's transforms. A <code className="font-mono text-xs">*</code> row is the fallback.
      </p>
      {tables.length === 0 && <p className="px-5 py-4 text-sm text-muted">This bank doesn't use any lookup tables.</p>}
      <div className="space-y-4 px-5 py-4">
        {tables.map(([name, table]) => {
          const rows = Object.entries(table)
          return (
            <div key={name} className="rounded-md border border-line">
              <div className="flex items-center gap-2 border-b border-line px-3 py-2">
                <input aria-label="Table name" defaultValue={name} onBlur={(e) => e.target.value && e.target.value !== name && renameTable(name, e.target.value)}
                  className={`${inputClass} max-w-56 font-mono text-xs`} />
                <span className="flex-1" />
                <button onClick={() => removeTable(name)} className="rounded-md px-2 py-1 text-sm text-muted hover:bg-bad-soft hover:text-bad">Remove table</button>
              </div>
              <div className="space-y-1 px-3 py-2">
                {rows.map(([k, v], i) => (
                  <div key={i} className="flex items-center gap-2">
                    <input aria-label="From" value={k} onChange={(e) => setTable(name, rows.map((r, j) => (j === i ? [e.target.value, r[1]] : r)))}
                      className={`${inputClass} text-xs`} placeholder="PayRelay value" />
                    <span className="text-muted" aria-hidden>to</span>
                    <input aria-label="To" value={v} onChange={(e) => setTable(name, rows.map((r, j) => (j === i ? [r[0], e.target.value] : r)))}
                      className={`${inputClass} font-mono text-xs`} placeholder="Bank code" />
                    <button onClick={() => setTable(name, rows.filter((_, j) => j !== i))} aria-label="Remove row"
                      className="rounded-md px-2 py-1 text-sm text-muted hover:bg-bad-soft hover:text-bad">Remove</button>
                  </div>
                ))}
                <button onClick={() => setTable(name, [...rows, ['', '']])} className="mt-1 text-sm text-brand hover:underline">Add row</button>
              </div>
            </div>
          )
        })}
      </div>
    </Panel>
  )
}
