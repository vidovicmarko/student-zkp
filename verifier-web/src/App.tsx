import { useState } from 'react'
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

export default function App() {
  const [phase, setPhase] = useState<Phase>('idle')
  const [input, setInput] = useState('')
  const [result, setResult] = useState<VerifyResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function handleVerify() {
    setPhase('verifying')
    setError(null)
    setResult(null)
    try {
      const r = await verifySdJwtVc(input.trim(), ISSUER_BASE_URL)
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
          <h2>1. Paste the credential</h2>
          <p style={muted}>
            Obtain one by running <code>./scripts/demo.sh</code> or{' '}
            <code>
              curl -X POST {ISSUER_BASE_URL}/dev/credential/0036123456
            </code>
            . Copy the <code>sdJwt</code> field from the response.
          </p>
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="eyJhbGci...~WyJhYmMi...~..."
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

const container: React.CSSProperties = {
  maxWidth: 720,
  margin: '40px auto',
  padding: '0 24px',
  fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif',
}
const muted: React.CSSProperties = { color: '#666', fontSize: 14 }
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
  marginTop: 16,
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
