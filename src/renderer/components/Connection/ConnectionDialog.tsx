import { tr, useLocale } from '../../i18n'
import { useState, useEffect } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { ConnectionConfig, ConnectionRequest, ConnectionTestResult } from '../../types/connection'
import './ConnectionDialog.css'
import { apiFetch } from '../../utils/api'

const DIALECTS = [
  { value: 'postgresql', label: 'PostgreSQL', defaultPort: 5432 },
  { value: 'mysql', label: 'MySQL', defaultPort: 3306 },
  { value: 'sqlserver', label: 'SQL Server', defaultPort: 1433 },
  { value: 'oracle', label: 'Oracle', defaultPort: 1521 },
]

interface MigrationState { status: string; unresolved: number; errorReason?: string; sourceHash?: string; sourcePresent?: boolean; degraded: boolean }
interface ConnectionDialogProps {
  backendUrl?: string | null
  onCredentialsChanged?: () => Promise<void>
  editingConnection?: ConnectionConfig
  saveError?: string | null
  onSave: (id: string | undefined, request: ConnectionRequest) => Promise<boolean>
  onDelete?: (id: string) => void | Promise<boolean>
  onCancel: () => void
  onTest: (request: ConnectionRequest) => Promise<ConnectionTestResult>
}

export default function ConnectionDialog({ backendUrl, onCredentialsChanged, editingConnection, saveError: connError, onSave, onDelete, onCancel, onTest }: ConnectionDialogProps) {
  useLocale()
  const [name, setName] = useState(editingConnection?.name ?? '')
  const [host, setHost] = useState(editingConnection?.host ?? 'localhost')
  const [port, setPort] = useState(String(editingConnection?.port ?? 5432))
  const [database, setDatabase] = useState(editingConnection?.database ?? '')
  const [username, setUsername] = useState(editingConnection?.username ?? '')
  const [password, setPassword] = useState('')
  const [noPassword, setNoPassword] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [migration, setMigration] = useState<MigrationState | null>(null)
  const [migrationError, setMigrationError] = useState<string | null>(null)
  const [migrationBusy, setMigrationBusy] = useState(false)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  useEffect(() => {
    if (!backendUrl) return
    const controller = new AbortController()
    void apiFetch(`${backendUrl}/api/connections/credentials/migration`, { signal: controller.signal }).then(async res => {
      if (!res.ok) throw new Error(tr("Could not read credential migration status. Retry loading."))
      const data = await res.json()
      if (!controller.signal.aborted) setMigration(data)
    }).catch(() => { if (!controller.signal.aborted) setMigrationError(tr("Could not read credential migration status. Retry loading.")) })
    return () => controller.abort()
  }, [backendUrl])
  const migrationAction = async (cleanup: boolean) => {
    if (!backendUrl || migrationBusy) return
    setMigrationBusy(true)
    setMigrationError(null)
    try {
      const res = await apiFetch(`${backendUrl}/api/connections/credentials/migration/${cleanup ? 'cleanup' : 'retry'}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: cleanup ? JSON.stringify({ sourceHash: migration?.sourceHash, confirmDiscard }) : undefined
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.message || tr("The migration operation could not be confirmed. Retry."))
      setMigration(data)
      setConfirmDiscard(false)
      await onCredentialsChanged?.()
    } catch (error) { setMigrationError(error instanceof Error ? error.message : tr("The migration operation could not be confirmed. Retry.")) }
    finally { setMigrationBusy(false) }
  }
  const [dialect, setDialect] = useState(editingConnection?.dialect ?? 'postgresql')

  const [connectionType, setConnectionType] = useState<'SERVICE_NAME' | 'SID'>(
    (editingConnection?.connectionType as 'SERVICE_NAME' | 'SID') ?? 'SERVICE_NAME'
  )

  const [trustServerCertificate, setTrustServerCertificate] = useState<boolean>(
    editingConnection?.trustServerCertificate ?? false
  )

  useEffect(() => {
    const dialectConfig = DIALECTS.find(d => d.value === dialect)
    if (dialectConfig && !editingConnection?.port) {
      setPort(String(dialectConfig.defaultPort))
    }
  }, [dialect, editingConnection?.port])

  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null)
  const [testing, setTesting] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const handleTest = async () => {
    setTesting(true)
    setTestResult(null)
    const result = await onTest({
      name, host, port: Number(port), database, username, password, dialect,
      ...(noPassword ? { noPassword: true } : {}),
      ...(editingConnection ? { connectionId: editingConnection.id } : {}),
      connectionType: dialect === 'oracle' ? connectionType : undefined,
      trustServerCertificate: dialect === 'sqlserver' ? trustServerCertificate : undefined,
    })
    setTestResult(result)
    setTesting(false)
  }

  const handleSave = async () => {
    setSaving(true)
    setSaveError(null)
    const ok = await onSave(editingConnection?.id, {
      name, host, port: Number(port), database, username, password, dialect,
      ...(noPassword ? { noPassword: true } : {}),
      ...(editingConnection ? { connectionId: editingConnection.id } : {}),
      connectionType: dialect === 'oracle' ? connectionType : undefined,
      trustServerCertificate: dialect === 'sqlserver' ? trustServerCertificate : undefined,
    })
    if (!ok) {
      setSaving(false)
      setSaveError(tr("Could not confirm the connection was saved. Retry saving."))
      return
    }
    setSaving(false)
  }

  const isValid = name.trim() && host.trim() && database.trim() && username.trim() && Number(port) > 0

  const handleOpenChange = (open: boolean) => {
    if (!open && !saving && !testing && !deleting && !migrationBusy) onCancel()
  }

  return (
    <Dialog.Root open onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog" aria-label={tr("Connection settings")} aria-describedby={undefined}>
          <Dialog.Title className="dialog-title">
            {editingConnection ? tr("Edit Connection") : tr("New Connection")}
          </Dialog.Title>

          <div className="dialog-form">
            <div className="form-row form-row-2">
              <div className="form-group">
                <label className="form-label">{tr("Name")}</label>
                <input className="form-input" value={name} onChange={e => setName(e.target.value)} placeholder={tr("My Database")} autoFocus />
              </div>
              <div className="form-group">
                <label className="form-label">{tr("Database Type")}</label>
                <select className="form-input" value={dialect} onChange={e => setDialect(e.target.value)}>
                  {DIALECTS.map(d => (
                    <option key={d.value} value={d.value}>{d.label}</option>
                  ))}
                </select>
              </div>
              {dialect === 'oracle' && (
                <div className="form-group" style={{ marginTop: '8px' }}>
                  <label className="form-label">{tr("Connection Type")}</label>
                  <select
                    className="form-input"
                    value={connectionType}
                    onChange={e => setConnectionType(e.target.value as 'SERVICE_NAME' | 'SID')}
                  >
                    <option value="SERVICE_NAME">{tr("Service Name")}</option>
                    <option value="SID">SID</option>
                  </select>
                </div>
              )}
              {dialect === 'sqlserver' && (
                <div className="form-group" style={{ marginTop: 8 }}>
                  <label className="form-label">
                    <input
                      type="checkbox"
                      className="form-input"
                      style={{ width: 'auto', marginRight: 8 }}
                      checked={trustServerCertificate}
                      onChange={(e) => setTrustServerCertificate(e.target.checked)}
                    />
                    {tr("Trust server certificate (skip TLS certificate verification)")}{" "}</label>
                </div>
              )}
            </div>

            <div className="form-row form-row-3">
              <div className="form-group">
                <label className="form-label">{tr("Host")}</label>
                <input className="form-input" value={host} onChange={e => setHost(e.target.value)} placeholder="localhost" />
              </div>
              <div className="form-group form-group-sm">
                <label className="form-label">{tr("Port")}</label>
                <input className="form-input" type="number" value={port} onChange={e => setPort(e.target.value)} />
              </div>
              <div className="form-group">
                <label className="form-label">{tr("Database")}</label>
                <input
                  className="form-input"
                  value={database}
                  onChange={e => setDatabase(e.target.value)}
                  placeholder={dialect === 'oracle' && connectionType === 'SID' ? 'SID' : 'mydb'}
                />
              </div>
            </div>

            <div className="form-row form-row-2">
              <div className="form-group">
                <label className="form-label">{tr("Username")}</label>
                <input className="form-input" value={username} onChange={e => setUsername(e.target.value)} placeholder="username" />
              </div>
              <div className="form-group">
                <label className="form-label">{tr("Password")}</label>
                <input className="form-input" type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder={tr("Enter password")} />
              </div>
            </div>
          </div>

          {(migrationError || (migration && (migration.degraded || migration.sourcePresent))) && <div className="test-result" role="status" aria-label={tr("Legacy credential migration")}>
            {migration?.errorReason === 'CREDENTIAL_BACKEND_UNAVAILABLE' && <p role="alert">{tr("Connection credentials require Windows DPAPI. Credentials are unavailable on this platform.")}</p>}
            {migrationError && <p role="alert">{migrationError}</p>}
            <details>
              <summary>{migration?.degraded
                ? tr("Legacy passwords need review ({0} unresolved records)", migration.unresolved)
                : migrationError ? tr("Legacy password recovery") : tr("Legacy password file retained")}</summary>
              <p>{tr("This status concerns previously saved passwords, including records that may belong to other data directories. It does not report the result of testing or saving this connection.")}</p>
              {!editingConnection && <p>{tr("You can enter the password above and test or save this new connection without removing the old file.")}</p>}
              {migration && <p>{tr("Legacy password migration:")}{" "}{migration.status}{tr(". Unresolved records:")}{" "}{migration.unresolved}.</p>}
              <button className="dialog-btn dialog-btn-secondary" disabled={migrationBusy || saving || testing || deleting} onClick={() => void migrationAction(false)}>{tr("Retry migration")}</button>
              {migration?.sourcePresent && <>
                <p>{tr("The old password file is retained for recovery. Removing it may affect other data directories. Unresolved records cannot be recovered from it afterward. Close older app versions before removing it.")}</p>
                <label><input type="checkbox" checked={confirmDiscard} onChange={e => setConfirmDiscard(e.target.checked)} /> {" "}{tr("I confirm removal of the old password file and its unresolved records.")}</label>
                <button className="dialog-btn dialog-btn-danger" disabled={!confirmDiscard || migrationBusy || saving || testing || deleting} onClick={() => void migrationAction(true)}>{tr("Remove old password file")}</button>
              </>}
            </details>
          </div>}
          {editingConnection?.credentialState && (
            <div className="test-result" role="status" aria-label={tr("Credential status")}>
              {editingConnection.credentialState.intent === 'AVAILABLE' ? tr("Password protected by this Windows user. Leave the password empty to keep it.")
                : editingConnection.credentialState.intent === 'NO_PASSWORD' ? tr("This connection uses no password.")
                : editingConnection.credentialState.intent === 'UNAVAILABLE' ? tr("Connection credentials require Windows DPAPI. Credentials are unavailable on this platform.")
                : editingConnection.credentialState.intent === 'NEEDS_REENTRY' ? tr("The saved password cannot be unlocked. Enter a replacement password or explicitly confirm that this connection uses no password.")
                : tr("Legacy password settings are missing or ambiguous. Enter a password or explicitly confirm that this connection uses no password.")}
            </div>
          )}
          <label className="form-label">
            <input type="checkbox" checked={noPassword} onChange={e => setNoPassword(e.target.checked)} />
            {' '}{tr("This connection uses no password")}{" "}</label>
          {(saveError || connError) && (
            <div className="test-result test-error">
              {connError || saveError}
            </div>
          )}

          {testResult && (
            <div className={`test-result ${testResult.success ? 'test-success' : 'test-error'}`}>
              {testResult.success
                ? tr("Connected ({0}ms) — {1} {2}", testResult.latency, DIALECTS.find(d => d.value === dialect)?.label ?? dialect, testResult.serverVersion)
                : testResult.errorReason === 'connect-timeout'
                  // The backend owns this text and it already states the deadline; prefixing
                  // it with "Connection failed:" would read as two contradictory verdicts.
                  ? testResult.error || tr("Connection timed out")
                  : tr("Connection failed: {0}", testResult.error)}
            </div>
          )}

          <div className="dialog-actions">
            {editingConnection && onDelete && (
              <button
                className="dialog-btn dialog-btn-danger"
                disabled={deleting || saving || testing || migrationBusy}
                onClick={async () => {
                  setDeleting(true)
                  try {
                    const ok = await onDelete(editingConnection.id)
                    if (ok === false) setSaveError(tr("Could not delete the connection. Retry deleting."))
                  } finally { setDeleting(false) }
                }}
                style={{ marginRight: 'auto' }}
              >
                {tr("Delete")}{" "}</button>
            )}
            <Dialog.Close asChild>
              <button className="dialog-btn dialog-btn-secondary" disabled={saving || testing || deleting || migrationBusy}>
                {tr("Cancel")}{" "}</button>
            </Dialog.Close>
            <button
              className="dialog-btn dialog-btn-ghost"
              onClick={handleTest}
              disabled={!isValid || testing || saving || deleting || migrationBusy}
            >
              {testing ? tr("Testing...") : tr("Test Connection")}
            </button>
            <button
              className="dialog-btn dialog-btn-primary"
              onClick={handleSave}
              disabled={!isValid || saving || testing || deleting || migrationBusy}
            >
              {saving ? tr("Saving...") : tr("Save")}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
