import { useMemo, useState } from 'react'
import { verifySdJwtVc, type VerifyResult } from './lib/verify'

// Verifier web app (final_plan §5.4). All crypto + validation runs client-side
// — zero data retention by construction. Phase 1 ships a paste-in flow; QR
// scanning is a Phase 2 addition (@zxing/library on top of getUserMedia).
//
// Issuer base URL is configurable via VITE_ISSUER_BASE_URL so the same bundle
// verifies against localhost in dev and a real issuer in prod.
const ISSUER_BASE_URL =
  (import.meta.env.VITE_ISSUER_BASE_URL as string | undefined) ??
  'http://localhost:8080'

// What this verifier is asking the holder to reveal. Phase 1.5 ships a DCQL
// parser and this becomes configurable per deployment.
const DCQL_REQUEST = {
  credentials: [
    {
      id: 'student-discount',
      format: 'vc+sd-jwt',
      meta: { vct_values: ['https://studentzk.eu/types/student/v1'] },
      claims: [{ path: ['is_student'] }, { path: ['age_equal_or_over'] }],
    },
  ],
}

type Phase = 'idle' | 'verifying' | 'verified' | 'failed'

function generateNonce(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export default function App() {
  const audience = useMemo(() => window.location.origin, [])
  const [nonce, setNonce] = useState<string>(() => generateNonce())
  const [requireKeyBinding, setRequireKeyBinding] = useState(true)

  const [phase, setPhase] = useState<Phase>('idle')
  const [input, setInput] = useState('')
  const [result, setResult] = useState<VerifyResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function handleVerify() {
    setPhase('verifying')
    setError(null)
    setResult(null)
    try {
      const r = await verifySdJwtVc(input.trim(), ISSUER_BASE_URL, {
        expectedNonce: nonce,
        expectedAudience: audience,
        requireKeyBinding,
      })
      setResult(r)
      setPhase('verified')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setPhase('failed')
    }
  }

  function reset() {
    setPhase('idle')
    setInput('')
    setResult(null)
    setError(null)
    setNonce(generateNonce())
  }

  async function copyChallenge() {
    await navigator.clipboard.writeText(JSON.stringify({ nonce, audience }, null, 2))
  }

  return (
    <div style={container}>
      <header style={{ marginBottom: 24 }}>
        <h1 style={{ margin: 0 }}>StudentZK Verifier</h1>
        <p style={{ color: '#666', marginTop: 4 }}>
          Proves <code>is_student</code> and <code>age_equal_or_over.18</code> — no
          name, no DOB, no student number.
        </p>
      </header>

      {phase === 'idle' && (
        <section>
          <h2>1. Send this challenge to the wallet</h2>
          <p style={muted}>
            Open the wallet's credential detail screen, tap <strong>Present</strong>,
            and paste the nonce + audience below. The wallet signs a Key-Binding JWT
            with its StrongBox key proving this credential lives on that phone.
          </p>
          <div style={challengeBox}>
            <ChallengeRow label="nonce" value={nonce} mono />
            <ChallengeRow label="audience" value={audience} mono />
            <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
              <button onClick={copyChallenge} style={secondaryButton}>
                Copy as JSON
              </button>
              <button onClick={() => setNonce(generateNonce())} style={secondaryButton}>
                Regenerate nonce
              </button>
            </div>
          </div>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12, fontSize: 14 }}>
            <input
              type="checkbox"
              checked={requireKeyBinding}
              onChange={(e) => setRequireKeyBinding(e.target.checked)}
            />
            Require Key-Binding JWT (reject device-bound credentials presented without one)
          </label>

          <h2 style={{ marginTop: 24 }}>2. Paste the wallet's presentation</h2>
          <p style={muted}>
            For a non-bound dev credential, run <code>./scripts/demo.sh</code> and paste
            its <code>sdJwt</code>. For a device-bound presentation, paste the wallet
            output (SD-JWT + KB-JWT).
          </p>
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="eyJhbGci...~WyJhYmMi...~eyJ0eXAi..."
            style={textarea}
          />
          <div style={{ marginTop: 12 }}>
            <button
              onClick={handleVerify}
              disabled={input.trim().length === 0}
              style={primaryButton}
            >
              Verify
            </button>
          </div>
          <details style={{ marginTop: 24 }}>
            <summary style={muted}>DCQL request (what we are asking for)</summary>
            <pre style={pre}>{JSON.stringify(DCQL_REQUEST, null, 2)}</pre>
          </details>
        </section>
      )}

      {phase === 'verifying' && <p>Verifying…</p>}

      {phase === 'verified' && result && (
        <section>
          <div style={result.revoked === true ? banner.bad : banner.good}>
            {result.revoked === true
              ? '✗  Credential REVOKED'
              : result.signatureValid
                ? '✓  Credential verified'
                : 'signature invalid'}
            {result.revoked === null && (
              <span style={{ marginLeft: 8, fontSize: 12 }}>
                (status list unreachable — result shown against last-known state)
              </span>
            )}
          </div>

          <KeyBindingBadge result={result} />

          <h3 style={{ marginTop: 24 }}>Revealed attributes</h3>
          <table style={table}>
            <tbody>
              {Object.entries(result.revealed).map(([k, v]) => (
                <tr key={k}>
                  <td style={cellKey}>{k}</td>
                  <td style={cellVal}>
                    <code>{JSON.stringify(v)}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <p style={muted}>
            Issuer: <code>{result.issuer}</code>
            <br />
            Type: <code>{result.vct}</code>
            <br />
            Valid until: <code>{result.validUntil ?? '—'}</code>
            <br />
            Hidden disclosures still bound to this credential:{' '}
            <strong>{result.hiddenCount}</strong>
          </p>

          <button onClick={reset} style={secondaryButton}>
            Verify another
          </button>
        </section>
      )}

      {phase === 'failed' && (
        <section>
          <div style={banner.bad}>✗  Verification failed</div>
          <pre style={{ ...pre, borderColor: '#e88' }}>{error}</pre>
          <button onClick={reset} style={secondaryButton}>
            Try again
          </button>
        </section>
      )}
    </div>
  )
}

function ChallengeRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
      <span style={{ minWidth: 80, color: '#666', fontSize: 13 }}>{label}</span>
      <code style={{ fontSize: 13, fontFamily: mono ? 'ui-monospace, monospace' : undefined, wordBreak: 'break-all' }}>
        {value}
      </code>
    </div>
  )
}

function KeyBindingBadge({ result }: { result: VerifyResult }) {
  const kb = result.keyBinding
  if (kb.state === 'absent') {
    return (
      <div style={{ ...banner.warn, marginTop: 12 }}>
        ⚠  No Key-Binding JWT — accepting unbound credential. The verifier cannot prove
        this credential lives on the holder's phone.
      </div>
    )
  }
  if (kb.state === 'failed') {
    return (
      <div style={{ ...banner.bad, marginTop: 12 }}>
        ✗  Key-Binding rejected: {kb.reason}
      </div>
    )
  }
  return (
    <div style={{ ...banner.good, marginTop: 12 }}>
      ✓  Device-bound (KB-JWT verified against StrongBox-pinned cnf key)
    </div>
  )
}

const container: React.CSSProperties = {
  maxWidth: 720,
  margin: '40px auto',
  padding: '0 24px',
  fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif',
}
const muted: React.CSSProperties = { color: '#666', fontSize: 14 }
const challengeBox: React.CSSProperties = {
  border: '1px solid #ccd',
  borderRadius: 6,
  padding: 12,
  background: '#f7f9ff',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
}
const textarea: React.CSSProperties = {
  width: '100%',
  minHeight: 140,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  padding: 12,
  borderRadius: 6,
  border: '1px solid #ccc',
  resize: 'vertical',
}
const pre: React.CSSProperties = {
  background: '#f7f7f7',
  border: '1px solid #e0e0e0',
  borderRadius: 6,
  padding: 12,
  fontSize: 12,
  overflow: 'auto',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
}
const primaryButton: React.CSSProperties = {
  padding: '10px 20px',
  fontSize: 15,
  background: '#1d4ed8',
  color: 'white',
  border: 'none',
  borderRadius: 6,
  cursor: 'pointer',
}
const secondaryButton: React.CSSProperties = {
  padding: '8px 16px',
  fontSize: 14,
  background: 'transparent',
  color: '#1d4ed8',
  border: '1px solid #1d4ed8',
  borderRadius: 6,
  cursor: 'pointer',
  marginTop: 0,
}
const banner = {
  good: {
    padding: 16,
    background: '#e8f6ea',
    border: '1px solid #a5d6a7',
    borderRadius: 6,
    color: '#1b5e20',
    fontWeight: 600,
  } as React.CSSProperties,
  bad: {
    padding: 16,
    background: '#fdecea',
    border: '1px solid #f5a097',
    borderRadius: 6,
    color: '#b71c1c',
    fontWeight: 600,
  } as React.CSSProperties,
  warn: {
    padding: 16,
    background: '#fff8e1',
    border: '1px solid #ffd54f',
    borderRadius: 6,
    color: '#7c6a14',
    fontWeight: 600,
  } as React.CSSProperties,
}
const table: React.CSSProperties = {
  borderCollapse: 'collapse',
  width: '100%',
  marginTop: 8,
  fontSize: 14,
}
const cellKey: React.CSSProperties = {
  border: '1px solid #e0e0e0',
  padding: '6px 10px',
  background: '#fafafa',
  width: '35%',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
}
const cellVal: React.CSSProperties = {
  border: '1px solid #e0e0e0',
  padding: '6px 10px',
}
