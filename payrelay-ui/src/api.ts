// Types mirror the Spring Boot DTOs in payrelay-api and bank-simulator.

export type PaymentStatus = 'RECEIVED' | 'PROCESSING' | 'RETRY_SCHEDULED' | 'COMPLETED' | 'FAILED' | 'DEAD_LETTERED'
export type PaymentStep = 'KYC_CHECK' | 'RISK_CHECK' | 'BANK_TRANSFER'
export type AttemptOutcome = 'SUCCESS' | 'RETRYABLE_FAILURE' | 'REJECTED' | 'CONFIG_ERROR'

export const STEPS: PaymentStep[] = ['KYC_CHECK', 'RISK_CHECK', 'BANK_TRANSFER']

export interface PaymentSummary {
  id: string
  idempotencyKey: string
  customerId: string
  amount: number
  currency: string
  beneficiaryName: string
  bankCode: string
  status: PaymentStatus
  currentStep: PaymentStep | null
  totalAttempts: number
  failureReason: string | null
  bankReference: string | null
  nextAttemptAt: string | null
  createdAt: string
  completedAt: string | null
}

export interface Attempt {
  id: string
  step: PaymentStep
  attemptNumber: number
  outcome: AttemptOutcome
  httpStatus: number | null
  request: string | null
  response: string | null
  error: string | null
  durationMs: number
  startedAt: string
  configVersion: number | null
  note: string | null
}

export interface PaymentDetail extends PaymentSummary {
  correlationId: string
  customerPan: string
  accountNumber: string
  ifsc: string
  purpose: string | null
  stepAttempts: number
  kycReference: string | null
  riskScore: number | null
  updatedAt: string
  attempts: Attempt[]
}

export interface Page<T> {
  content: T[]
  page: { size: number; number: number; totalElements: number; totalPages: number }
}

export interface BankStats {
  bankCode: string
  total: number
  completed: number
  failed: number
  deadLettered: number
  inFlight: number
}

export interface Stats {
  byStatus: Record<PaymentStatus, number>
  total: number
  successRate: number
  recoveredAfterFailure: number
  avgAttemptsPerCompleted: number
  latencyP50Ms: number | null
  latencyP95Ms: number | null
  banks: BankStats[]
  throughput: { minute: string; created: number; completed: number; failed: number }[]
}

export interface ChaosConfig {
  failureRate: number
  lostResponseRate: number
  latencyMs: number
}

export interface TargetStats {
  requests: number
  injectedFailures: number
  lostResponses: number
  uniqueOperations: number
  idempotentReplays: number
  businessRejections: number
  amountMoved: number
}

// ---- Bank configs (mirror com.payrelay.api.bank.BankSpec) ----

export type AuthType = 'API_KEY' | 'OAUTH2_CLIENT_CREDENTIALS' | 'HMAC_SHA256'

export interface FieldRule {
  target: string
  source?: string | null
  constant?: string | null
  transforms?: string[] | null
  required?: boolean | null
  defaultValue?: string | null
}

export interface BankAuth {
  type: AuthType
  secretRef?: string
  header?: string
  prefix?: string
  tokenPath?: string
  clientId?: string
  scope?: string
  keyId?: string
  signatureHeader?: string
  timestampHeader?: string
  keyIdHeader?: string
}

export interface BankSpec {
  endpoint: { path: string }
  auth: BankAuth
  request: { headers: FieldRule[]; body: FieldRule[] }
  response: {
    statusPath: string
    successValues: string[]
    rejectedValues?: string[]
    referencePath: string
    reasonPath?: string
  }
  lookups: Record<string, Record<string, string>>
}

export interface BankSummary {
  code: string
  displayName: string
  enabled: boolean
  authType: AuthType | null
  bodyFields: number
  version: number
  updatedAt: string
}

export interface BankView {
  code: string
  displayName: string
  enabled: boolean
  version: number
  updatedAt: string
  spec: BankSpec
}

export interface BankPreview {
  method: string
  path: string | null
  headers: Record<string, string>
  body: unknown
  errors: string[]
  sample: Record<string, unknown>
}

export interface BankHistoryEntry {
  version: number
  displayName: string
  enabled: boolean
  changedAt: string
  spec: BankSpec
}

export interface BankMeta {
  sources: { name: string; description: string }[]
  transforms: { name: string; description: string; takesArgument: boolean }[]
  authTypes: AuthType[]
}

export class ApiError extends Error {
  constructor(public status: number, message: string, public details: string[] = []) {
    super(message)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  const text = await res.text()
  const body = text ? JSON.parse(text) : undefined
  if (!res.ok) {
    throw new ApiError(res.status, body?.detail ?? body?.message ?? `Request failed with ${res.status}`, body?.errors ?? [])
  }
  return body as T
}

export const api = {
  stats: () => request<Stats>('/api/stats'),
  banks: () => request<BankSummary[]>('/api/banks'),
  bankMeta: () => request<BankMeta>('/api/banks/meta'),
  bank: (code: string) => request<BankView>(`/api/banks/${code}`),
  saveBank: (code: string, body: { displayName: string; enabled: boolean; version: number; spec: BankSpec }) =>
    request<BankView>(`/api/banks/${code}`, { method: 'PUT', body: JSON.stringify(body) }),
  previewBank: (code: string, spec: BankSpec) =>
    request<BankPreview>(`/api/banks/${code}/preview`, { method: 'POST', body: JSON.stringify({ spec }) }),
  bankHistory: (code: string) => request<BankHistoryEntry[]>(`/api/banks/${code}/history`),
  payments: (params: { status?: string; bankCode?: string; page?: number; size?: number }) => {
    const q = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => v !== undefined && v !== '' && q.set(k, String(v)))
    return request<Page<PaymentSummary>>(`/api/payments?${q}`)
  },
  payment: (id: string) => request<PaymentDetail>(`/api/payments/${id}`),
  replay: (id: string) => request<PaymentDetail>(`/api/payments/${id}/replay`, { method: 'POST' }),
  sendTraffic: (count: number, bankCode?: string) =>
    request<{ created: number; replayed: number }>('/api/demo/load', {
      method: 'POST',
      body: JSON.stringify({ count, bankCode }),
    }),
  resetDemo: () => request<void>('/api/demo/reset', { method: 'POST' }),

  chaos: () => request<Record<string, ChaosConfig>>('/sim/chaos'),
  setChaos: (target: string, config: ChaosConfig) =>
    request<Record<string, ChaosConfig>>(`/sim/chaos/${target}`, { method: 'PUT', body: JSON.stringify(config) }),
  preset: (name: string) => request<Record<string, ChaosConfig>>(`/sim/chaos/presets/${name}`, { method: 'POST' }),
  resetChaos: () => request<Record<string, ChaosConfig>>('/sim/chaos/reset', { method: 'POST' }),
  simStats: () => request<Record<string, TargetStats>>('/sim/chaos/stats'),
  revokeZenithTokens: () => request<{ revoked: number }>('/sim/chaos/banks/zenith/revoke-tokens', { method: 'POST' }),
}
