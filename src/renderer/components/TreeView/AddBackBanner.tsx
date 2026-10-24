import { tr, useLocale } from '../../i18n'
import { describeReverseIndexReason, type ReverseIndexDegradedReason } from '../../utils/dagReverseIndex'
import './AddBackBanner.css'

export interface AddBackBannerState {
  active: boolean
  seedLabel: string
  breadcrumb: string[]
  /** False when the walk never resolved a parent all the way to the original SQL. */
  reachesOrigin: boolean
  /** True only when the display root is the DAG root — the 原 SQL label gate
   *  (spec §4.3.4 #2). 沿用投影层的同名字段，不在本组件重述文案。 */
  isOriginRoot: boolean
  /** Index-level degradation — the copy comes from the single source, not from here. */
  degraded: boolean
  reason: ReverseIndexDegradedReason | null
  onExit: () => void
}

type AddBackBannerProps = Omit<AddBackBannerState, 'active'>

/**
 * 加回探索态横幅（spec §4.3.1 / §4.3.4）。它是唯一宣告「你已不在自上而下视图」
 * 的表面：显示种子、已走的逐跳链、以及索引不可信时的共享降级文案。
 *
 * 链文本自带终端标记（`ORIGIN_SUFFIX` / `UNRESOLVED_SUFFIX`，由投影产出），
 * 故横幅不重述；`reachesOrigin` 只驱动样式与可访问标题。
 *
 * `isOriginRoot` 是 spec §4.3.4 #2「显示树根才算原 SQL」的判据，一路从投影层
 * 透传到渲染层（App → FragmentTree → 本组件的 `addBack` 状态）：它由 `AddBackBannerState`
 * 携带、由渲染树根的那一层消费，**横幅不 destructure、也不重述它的文案**（同终端后缀的规矩）。
 */
export default function AddBackBanner({
  seedLabel,
  breadcrumb,
  reachesOrigin,
  degraded,
  reason,
  onExit,
}: AddBackBannerProps) {
  useLocale()
  const notice = describeReverseIndexReason(reason)

  return (
    <div className="add-back-banner" role="status">
      <div className="add-back-line">
        <span className="add-back-mode">{tr("Add-back mode")}</span>
        <span className="add-back-seed" title={seedLabel}>{tr("Seed:")}{" "}{seedLabel}</span>
        <button type="button" className="add-back-exit" onClick={onExit}>
          {tr("Back to top-down")}{" "}</button>
      </div>
      <div
        className={`add-back-path ${reachesOrigin ? 'add-back-path-origin' : 'add-back-path-unresolved'}`}
        title={reachesOrigin ? tr("Reached the original SQL") : tr("Did not reach the original SQL")}
      >
        {breadcrumb.join(' → ')}
      </div>
      {degraded && notice && (
        <div className="add-back-notice">{notice}</div>
      )}
    </div>
  )
}
