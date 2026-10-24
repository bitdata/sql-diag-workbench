// Synchronous gate: queued keyboard/Portal callbacks cannot race a React render.
let locked = false
export const isWorkspaceExitLocked = () => locked
export const setWorkspaceExitLocked = (value: boolean) => { locked = value }
