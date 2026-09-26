import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { authLabel, dateTime } from '../format'
import { ErrorNotice, Loading, PageHeader, Panel } from '../components/ui'

export function Banks() {
  const banks = useQuery({ queryKey: ['banks'], queryFn: api.banks })
  const navigate = useNavigate()

  return (
    <>
      <PageHeader
        title="Banks"
        description="Each bank has its own API: different URLs, field names, amount formats and authentication. PayRelay adapts through these configs, so a bank change is a reviewed config edit rather than a code change and a deploy."
      />
      {banks.error && <ErrorNotice error={banks.error} />}
      <Panel>
        {banks.isLoading && <Loading />}
        {banks.data && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead className="text-left text-xs text-muted">
                <tr className="border-b border-line">
                  <th className="px-5 py-2.5 font-medium">Bank</th>
                  <th className="px-3 py-2.5 font-medium">Status</th>
                  <th className="px-3 py-2.5 font-medium">Authentication</th>
                  <th className="px-3 py-2.5 text-right font-medium">Mapped fields</th>
                  <th className="px-3 py-2.5 text-right font-medium">Version</th>
                  <th className="px-5 py-2.5 font-medium">Last changed</th>
                </tr>
              </thead>
              <tbody>
                {banks.data.map((b) => (
                  <tr key={b.code} onClick={() => navigate(`/banks/${b.code}`)}
                    className="cursor-pointer border-b border-line last:border-0 hover:bg-canvas">
                    <td className="px-5 py-3">
                      <a href={`/banks/${b.code}`} onClick={(e) => { e.preventDefault(); navigate(`/banks/${b.code}`) }}
                        className="font-medium hover:text-brand">{b.displayName}</a>
                      <span className="ml-2 font-mono text-xs text-muted">{b.code}</span>
                    </td>
                    <td className="px-3 py-3">
                      {b.enabled ? <span className="text-ok">Active</span> : <span className="text-wait">Paused</span>}
                    </td>
                    <td className="px-3 py-3">{b.authType ? authLabel[b.authType] : '—'}</td>
                    <td className="px-3 py-3 text-right">{b.bodyFields}</td>
                    <td className="px-3 py-3 text-right">v{b.version}</td>
                    <td className="px-5 py-3 text-muted">{dateTime(b.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </>
  )
}
