import { useState } from 'react'
import { USER_PATTERN } from '../api'
import type { Mode, Session } from '../api'

interface Props {
  session: Session
  onChange: (session: Session) => void
}

const MODES: readonly { mode: Mode; label: string }[] = [
  { mode: 'engineer', label: '엔지니어' },
  { mode: 'operator', label: '운영자' },
]

/**
 * 모드 전환. 인증 없이 둔다(스펙 §8). 모드는 X-Actor 에 실려 registry 까지 간다(스펙 §7.1).
 * 규칙에 어긋난 사용자 이름은 입력란에만 두고 요청에는 싣지 않는다.
 */
export function ModeSwitch({ session, onChange }: Props) {
  const [draft, setDraft] = useState(session.user)
  const valid = USER_PATTERN.test(draft)
  return (
    <fieldset className="mode">
      <legend>모드</legend>
      {MODES.map(({ mode, label }) => (
        <label key={mode}>
          <input
            type="radio"
            name="mode"
            checked={session.mode === mode}
            onChange={() => onChange({ ...session, mode })}
          />
          {label}
        </label>
      ))}
      <label>
        사용자
        <input
          value={draft}
          aria-invalid={!valid}
          onChange={(event) => {
            const next = event.target.value
            setDraft(next)
            if (USER_PATTERN.test(next)) onChange({ ...session, user: next })
          }}
        />
      </label>
      {!valid && (
        <span className="invalid">
          사용자 이름은 영문·숫자·._- 로 1~64자입니다. 요청에는 직전 이름({session.user})을 씁니다
        </span>
      )}
    </fieldset>
  )
}
