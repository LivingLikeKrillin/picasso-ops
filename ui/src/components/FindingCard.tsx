import type { Finding } from '../api'
import { OWNER_LABEL, kindLabel } from '../labels'

interface Props {
  finding: Finding
  /** 링크를 누르면 그 기체의 상세를 연다. 없으면 링크를 그리지 않는다(이미 그 기체의 상세 안일 때). */
  onSelect?: (robotId: string) => void
}

/** 막힘이나 거절 한 건을 5칸으로 보인다(스펙 §7.4): 종류, 관측값과 기대값, 마지막 확인 시각, 해결 담당, 바로 갈 링크. */
export function FindingCard({ finding, onSelect }: Props) {
  return (
    <dl className="finding">
      <dt>종류</dt>
      <dd>{kindLabel(finding.kind)}</dd>
      <dt>관측값과 기대값</dt>
      <dd>
        {finding.observed} / 기대: {finding.expected}
      </dd>
      <dt>마지막 확인</dt>
      <dd>{finding.checkedAt}</dd>
      <dt>해결 담당</dt>
      <dd>
        {OWNER_LABEL[finding.owner]}({finding.inScreen ? '화면 안' : '화면 밖'}): {finding.action}
      </dd>
      {finding.target !== null && onSelect && (
        <>
          <dt>바로 가기</dt>
          <dd>
            <button type="button" className="link" onClick={() => onSelect(finding.target!)}>
              {finding.target} 상세
            </button>
          </dd>
        </>
      )}
    </dl>
  )
}
