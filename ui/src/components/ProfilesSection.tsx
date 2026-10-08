import { useState } from 'react'
import type { FormEvent } from 'react'
import { activateRevision, requestTest, submitRevision } from '../api'
import type { ProfileListView, RevisionView, Sent, Session, SuiteRun } from '../api'
import { TEST_REQUEST_LABEL } from '../labels'

const SUITES = ['CONTRACT', 'NEGATIVE', 'DETERMINISM'] as const

interface Props {
  view: ProfileListView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
  session: Session
  busy: boolean
  /** 조작을 보낸다. 결과 알림과 목록 다시 읽기는 부르는 쪽이 한다. */
  run: (what: string, operation: () => Promise<Sent>) => void
}

/**
 * «프로파일» 구역(P2·S1d 스펙 §9). 카탈로그 한 줄과 개정판 목록이다. 카탈로그는 registry 가 기동 때 동기화하므로 동기화
 * 버튼이 없다. 제출·시험 요청·활성화는 엔지니어 모드에서 한다. 시험 결과는 사람이 적지 않는다 — 현장의 실행기가 적는다.
 */
export function ProfilesSection({ view, opsError, session, busy, run }: Props) {
  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 칸이 없다. 없는 칸도 모름이다.
  const known = view !== null && view.catalog != null && view.revisions != null ? view : null
  const engineer = session.mode === 'engineer'
  const name = (row: RevisionView) => `${row.revision.vendor}/${row.revision.model}#${row.revision.revision}`

  return (
    <section aria-label="프로파일">
      <h2>프로파일</h2>
      {known === null ? (
        <p>모름: 프로파일 목록을 아직 읽지 못했습니다</p>
      ) : (
        <>
          {(known.asOf !== known.checkedAt || opsError !== null) && (
            <p className="stale">직전 값입니다 ({known.asOf} 기준)</p>
          )}
          <p>
            스킬 {known.catalog!.skillTypes.length}종, 계약 {known.catalog!.contractSemver}
          </p>
          {known.revisions!.length === 0 ? (
            <p>제출된 리비전이 없습니다</p>
          ) : (
            <table aria-label="리비전 목록">
              <thead>
                <tr>
                  <th>기종</th>
                  <th>번호</th>
                  <th>상태</th>
                  {SUITES.map((suite) => (
                    <th key={suite}>{suite}</th>
                  ))}
                  <th>시험 요청</th>
                  <th>활성화</th>
                  {engineer && <th>조작</th>}
                </tr>
              </thead>
              <tbody>
                {known.revisions!.map((row) => (
                  <tr key={row.revision.profileRevisionId}>
                    <td>
                      {row.revision.vendor}/{row.revision.model}
                    </td>
                    <td>{row.revision.revision}</td>
                    <td>
                      {row.revision.status}
                      {row.revision.status === 'DRAFT' && (
                        <details>
                          <summary>저장됨: 검증 실패</summary>
                          <ul>
                            {row.revision.reasons.map((reason) => (
                              <li key={reason}>{reason}</li>
                            ))}
                          </ul>
                        </details>
                      )}
                    </td>
                    {SUITES.map((suite) => (
                      <td key={suite}>
                        <SuiteCell run={row.revision.suites[suite]} />
                      </td>
                    ))}
                    <td>{TEST_REQUEST_LABEL[row.testRequest]}</td>
                    <td>
                      {row.revision.activatedBy !== null
                        ? `${row.revision.activatedBy} ${row.revision.activatedAt}`
                        : ''}
                    </td>
                    {engineer && (
                      <td>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() =>
                            run(`${name(row)} 시험 요청`, () => requestTest(session, row.revision.profileRevisionId))
                          }
                        >
                          시험 요청
                        </button>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() =>
                            run(`${name(row)} 활성화`, () => activateRevision(session, row.revision.profileRevisionId))
                          }
                        >
                          활성화
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
      {engineer ? (
        <SubmitForm
          busy={busy}
          onSubmit={(fileName, text) => run(`${coordinate(text) ?? fileName} 제출`, () => submitRevision(session, text))}
        />
      ) : (
        <p>프로파일 관리는 엔지니어 모드에서 합니다</p>
      )}
    </section>
  )
}

/**
 * 알림에 적을 기종·번호(`vendor/model#revision`). 조작 기록의 대상 칸과 같은 꼴이다. 읽지 못하면 널이고 알림은 파일 이름을 쓴다.
 * 문서의 옳고 그름은 registry 가 판정한다.
 */
function coordinate(text: string): string | null {
  try {
    const document = JSON.parse(text) as { vendor?: unknown; model?: unknown; revision?: unknown }
    const { vendor, model, revision } = document
    return typeof vendor === 'string' && typeof model === 'string' && typeof revision === 'number'
      ? `${vendor}/${model}#${revision}`
      : null
  } catch {
    return null
  }
}

/** 스위트 결과와 실행 주체. FAIL 이면 상세를 펼쳐 본다. */
function SuiteCell({ run }: { run: SuiteRun | undefined }) {
  if (run === undefined) return <>-</>
  const failures = run.detail?.failures ?? []
  return (
    <>
      {run.result} ({run.ranBy})
      {run.result === 'FAIL' && failures.length > 0 && (
        <details>
          <summary>상세</summary>
          <ul>
            {failures.map((failure) => (
              <li key={failure.check}>
                {failure.check}: 기대 {failure.expected}, 관측 {failure.observed}
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  )
}

/** 제출 폼. 고른 파일의 글자를 그대로 보낸다. 문서의 옳고 그름은 registry 가 판정한다. */
function SubmitForm({ busy, onSubmit }: { busy: boolean; onSubmit: (fileName: string, text: string) => void }) {
  const [file, setFile] = useState<File | null>(null)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (file !== null) void file.text().then((text) => onSubmit(file.name, text))
  }

  return (
    <form aria-label="리비전 제출" onSubmit={submit}>
      <label>
        프로파일 문서
        <input type="file" accept=".json,application/json" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
      </label>
      <button type="submit" disabled={file === null || busy}>
        제출
      </button>
    </form>
  )
}
