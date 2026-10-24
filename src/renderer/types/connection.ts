export interface ConnectionConfig {
  id: string
  name: string
  host: string
  port: number
  database: string
  username: string
  dialect: string
  keychainRef?: string
  credentialState?: { intent: string; errorReason?: string | null; degraded: boolean }
  readonly: boolean
  createdAt: string
  updatedAt: string
  connectionType?: 'SERVICE_NAME' | 'SID'
  trustServerCertificate?: boolean
}

export interface ConnectionRequest {
  noPassword?: boolean
  connectionId?: string
  name: string
  host: string
  port: number
  database: string
  username: string
  password: string
  dialect: string
  connectionType?: string
  trustServerCertificate?: boolean
}

export interface ConnectionTestResult {
  success: boolean
  latency?: number
  serverVersion?: string
  error?: string
  /**
   * Backend failure classification (spec 2026-10-08 §5.1). ABSENT on success — never
   * `null`/`''`. `'connect-timeout'` means the backend's own handshake deadline fired and
   * `error` is the application's locale-independent message; `'other'` means `error` is
   * whatever the driver reported.
   */
  errorReason?: string
  degraded?: boolean
}

