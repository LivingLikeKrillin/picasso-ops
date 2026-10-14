import type { PermitBlock } from '../api'
import { PERMIT_BLOCK_TEXT as TEXT, PERMIT_CAUSE_LABEL, WITHHELD_ISSUE_LABEL, WORK_PERMIT_LABEL } from '../labels'

/**
 * 작업 허가로 막힌 새 로봇 명령(ADR 52, 실행의 `permitBlock`). 어느 기체가, 무엇 때문에, 무엇이 바뀌면 풀리는지를 보인다. 읽기만 한다.
 *
 * 운영자 보류·결함 막음과 다른 표시다. 작업 허가는 안전 신호를 비춘 정보이고, 이 화면은 그것으로 picasso 가 새 명령을 내지 않고 있다는
 * 것만 말한다. 기체를 세우는 것은 안전 계통이다.
 */
export function PermitBlockNotice({ block }: { block: PermitBlock }) {
  return (
    <section className="permit-block" aria-label={TEXT.heading}>
      <h4>{TEXT.heading}</h4>
      <p>{TEXT.intro}</p>
      <dl>
        <dt>{TEXT.unit}</dt>
        <dd>{block.unitId}</dd>
        <dt>{TEXT.withheld}</dt>
        <dd>{WITHHELD_ISSUE_LABEL[block.withheld] ?? block.withheld}</dd>
        <dt>{TEXT.since}</dt>
        <dd>{block.since}</dd>
      </dl>
      <ul aria-label="허가를 기다리는 기체">
        {block.robots.map((robot) => (
          <li key={robot.robotId}>
            <strong>
              {TEXT.robot} {robot.robotId}: {WORK_PERMIT_LABEL[robot.permit] ?? robot.permit}
            </strong>
            <ul>
              {robot.reasons.map((reason, index) => (
                <li key={index}>
                  {TEXT.cause} {PERMIT_CAUSE_LABEL[reason.cause] ?? reason.cause} · {TEXT.subject} {reason.subject ?? TEXT.none} ·{' '}
                  {TEXT.resumeBasis} {reason.resumeBasis}
                  <small>
                    {' '}
                    ({TEXT.source} {reason.source ?? TEXT.none}, {TEXT.observedAt} {reason.observedAt ?? TEXT.none})
                  </small>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
      <p>{TEXT.notOperator}</p>
      <p>{TEXT.notSafety}</p>
    </section>
  )
}
