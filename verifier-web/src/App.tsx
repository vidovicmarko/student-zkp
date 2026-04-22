import { useState } from 'react'

type VerifierState = 'idle' | 'scanning' | 'verified' | 'failed'

export default function App() {
  const [state, setState] = useState<VerifierState>('idle')

  if (state === 'idle') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '2rem' }}>
        <h1>StudentZK Verifier</h1>
        <button onClick={() => setState('scanning')} style={{ padding: '0.75rem 2rem', fontSize: '1rem' }}>
          Start Scanning
        </button>
      </div>
    )
  }

  if (state === 'scanning') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '2rem' }}>
        <h1>Scan Credential QR</h1>
        {/* TODO: integrate @zxing/library for QR scanning — mount BrowserQRCodeReader here */}
        <div style={{ width: 300, height: 300, border: '2px dashed #ccc', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          Camera preview placeholder
        </div>
        <button onClick={() => setState('idle')} style={{ marginTop: '1rem' }}>
          Cancel
        </button>
      </div>
    )
  }

  if (state === 'verified') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '2rem' }}>
        <div style={{ fontSize: '4rem', color: 'green' }}>✓</div>
        <h2>Credential Verified</h2>
        {/* TODO: render selectively-disclosed attributes from the parsed SD-JWT-VC / BBS+ proof */}
        <div style={{ border: '1px solid #ccc', padding: '1rem', borderRadius: 8 }}>
          Disclosed attributes placeholder
        </div>
        <button onClick={() => setState('idle')} style={{ marginTop: '1rem' }}>
          Done
        </button>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '2rem' }}>
      <div style={{ fontSize: '4rem', color: 'red' }}>✗</div>
      <h2>Verification Failed</h2>
      <button onClick={() => setState('idle')} style={{ padding: '0.75rem 2rem', fontSize: '1rem' }}>
        Try Again
      </button>
    </div>
  )
}
