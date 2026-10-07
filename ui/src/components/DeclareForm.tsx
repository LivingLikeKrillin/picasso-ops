import { useState } from 'react'
import type { FormEvent } from 'react'

interface Props {
  onDeclare: (robotId: string, serialNumber: string, displayName: string | null) => void
  busy: boolean
}

/** 기체 선언(엔지니어 모드). 사이트는 운영 서비스가 `SITE_ID` 로 채우므로 입력란이 없다(스펙 §9). */
export function DeclareForm({ onDeclare, busy }: Props) {
  const [robotId, setRobotId] = useState('')
  const [serialNumber, setSerialNumber] = useState('')
  const [displayName, setDisplayName] = useState('')
  const ready = robotId.trim() !== '' && serialNumber.trim() !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!ready) return
    onDeclare(robotId.trim(), serialNumber.trim(), displayName.trim() === '' ? null : displayName.trim())
  }

  return (
    <form aria-label="기체 선언" onSubmit={submit}>
      <label>
        robot_id
        <input value={robotId} onChange={(event) => setRobotId(event.target.value)} />
      </label>
      <label>
        일련번호
        <input value={serialNumber} onChange={(event) => setSerialNumber(event.target.value)} />
      </label>
      <label>
        표시 이름
        <input value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
      </label>
      <button type="submit" disabled={!ready || busy}>
        선언
      </button>
    </form>
  )
}
