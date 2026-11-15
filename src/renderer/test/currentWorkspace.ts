import type { SessionWorkspaceState } from '../types/session'
import { IDENTITY_POLICY } from '../utils/workspaceIdentity'
/** Unit-only context fixture; real codec identities are covered by backend HTTP tests. */
export const currentWorkspace: SessionWorkspaceState = { sessionId:'test-session',tabName:'test',sql:'',identityPolicy:IDENTITY_POLICY,
  workspaceRevision:0,readOnly:false,isDirty:false,lastSavedAt:null,connectionId:null,dialect:'postgresql',
  fragmentTreeJson:null,selectedNodeId:null,workspaceMode:'data',viewerMode:'input',hydration:'ready' }
