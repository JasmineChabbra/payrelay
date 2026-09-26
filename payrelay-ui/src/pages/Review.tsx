import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { money, shortId, stepLabel, targetLabel, time } from '../format'
import { Button, ErrorNotice, Loading, PageHeader, Panel } from '../components/ui'

/** The dead-letter queue: payments whose retries ran out. A person decides when to try again. */
export function Review() {
  const qc = useQueryClient()
  const list = useQuery({
    queryKey: ['payments', 'DEAD_LETTERED'],
    queryFn: () => api.payments({ status: 'DEAD_LETTERED', size: 100 }),
    refetchInterval: 3000,
  })
  const replay = useMutation({
    mutationFn: (ids: string[]) => Promise.all(ids.map(api.replay)),
    onSuccess: () => qc.invalidateQueries(),
  })

  const items = list.data?.content ?? []

  return (
    <>
      <PageHeader
        title="Needs review"
        description="These payments failed on every retry, usually because a system was down for too long. Fix the cause, then replay. Each one resumes at the step that failed, so nothing that already happened runs again."
        action={items.length > 1 && (
          <Button onClick={() => replay.mutate(items.map((p) => p.id))} disabled={replay.isPending}>
            {replay.isPending ? 'Replaying…' : `Replay all ${items.length}`}
          </Button>
        )}
      />
      {list.error && <ErrorNotice error={list.error} />}
      {replay.error && <ErrorNotice error={replay.error} />}
      <Panel>
        {list.isLoading && <Loading />}
        {list.data && items.length === 0 && (
          <p className="px-5 py-10 text-sm text-muted">Nothing needs review. Payments land here only when every retry of a step fails.</p>
        )}
        {items.length > 0 && (
          <ul className="divide-y divide-line">
            {items.map((p) => (
              <li key={p.id} className="flex flex-wrap items-center justify-between gap-4 px-5 py-4">
                <div className="min-w-0">
                  <p className="text-sm">
                    <Link to={`/payments/${p.id}`} className="font-medium hover:text-brand">
                      {money(p.amount)} to {p.beneficiaryName}
                    </Link>
                    <span className="ml-2 font-mono text-xs text-muted">{shortId(p.id)}</span>
                  </p>
                  <p className="mt-0.5 text-sm text-muted">
                    Stuck at {p.currentStep ? stepLabel[p.currentStep].toLowerCase() : 'unknown step'} with {targetLabel(p.bankCode)}, last tried {time(p.createdAt)}
                  </p>
                  <p className="mt-0.5 line-clamp-1 text-xs text-dead" title={p.failureReason ?? ''}>{p.failureReason}</p>
                </div>
                <Button variant="secondary" onClick={() => replay.mutate([p.id])} disabled={replay.isPending}>Replay</Button>
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </>
  )
}
