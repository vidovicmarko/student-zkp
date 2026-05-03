// SD-JWT-VC + BBS-2023 verification. Supports both formats.
// SD-JWT-VC spec: https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/
// BBS spec: https://www.w3.org/TR/vc-data-integrity/ + @mattrglobal/bbs-signatures
import { createRemoteJWKSet, importJWK, jwtVerify, type JWK } from 'jose'
import pako from 'pako'

export interface VerifyResult {
  format: 'sd-jwt-vc' | 'bbs-2023'
  signatureValid: boolean
  revoked: boolean | null
  issuer: string
  vct: string
  validUntil: string | null
  revealed: Record<string, unknown>
  hiddenCount: number
  keyBinding: KeyBindingResult
  proofHash?: string
  dcqlValidation?: {
    valid: boolean
    errors: DcqlValidationError[]
  }
  raw: {
    header?: Record<string, unknown>
    payload?: Record<string, unknown>
    credential?: Record<string, unknown>
    proof?: Record<string, unknown>
    disclosures?: Array<{ name: string; value: unknown }>
  }
}

export type KeyBindingResult =
  | { state: 'absent' }
  | { state: 'verified'; cnfJwk: JWK; nonce: string; audience: string; iat: number }
  | { state: 'failed'; reason: string }

export interface DcqlCredential {
  id: string
  format: string | string[]
  meta?: { vct_values?: string[] }
  claims?: Array<{ path: string[] }>
}

export interface DcqlRequest {
  credentials: DcqlCredential[]
}

export interface DcqlValidationError {
  constraint: string
  detail: string
}

export interface VerifyOptions {
  /** Verifier's expected challenge nonce. If set, KB-JWT must echo this value. */
  expectedNonce?: string
  /** Verifier's expected audience (typically `window.location.origin`). */
  expectedAudience?: string
  /**
   * If true, presentations without a KB-JWT are rejected when the credential
   * carries a `cnf` claim. If false, missing KB-JWT is reported but not fatal.
   */
  requireKeyBinding?: boolean
}

const textEncoder = new TextEncoder()

function base64UrlToBytes(b64url: string): Uint8Array {
  const padded = b64url.replace(/-/g, '+').replace(/_/g, '/').padEnd(
    b64url.length + ((4 - (b64url.length % 4)) % 4),
    '=',
  )
  const bin = atob(padded)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function base64UrlDecodeString(b64url: string): string {
  return new TextDecoder().decode(base64UrlToBytes(b64url))
}

async function sha256Base64Url(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', textEncoder.encode(input))
  const bytes = new Uint8Array(digest)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/**
 * A KB-JWT is a 3-segment compact JWS: header.payload.sig (each base64url, no `~`).
 * The SD-JWT-VC compact form is `<jwt>~<disc1>~...~<discN>~[<kb-jwt>]`. After splitting
 * on `~` and dropping trailing empties, the last element is a KB-JWT iff it contains
 * exactly two `.` separators.
 */
function looksLikeKbJwt(s: string): boolean {
  return s.split('.').length === 3 && !s.includes('~')
}

// Flatten a nested object to dot-separated keys (for canonicalization).
function flattenObject(obj: unknown, prefix = ''): Record<string, string> {
  const result: Record<string, string> = {}
  if (obj === null || typeof obj !== 'object') {
    return result
  }
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v === null || typeof v !== 'object') {
      result[key] = JSON.stringify(v)
    } else {
      Object.assign(result, flattenObject(v, key))
    }
  }
  return result
}

export async function verifyBbsCredential(
  credentialJson: string,
  issuerBaseUrl: string,
): Promise<VerifyResult> {
  const { verifyProof } = await import('@mattrglobal/bbs-signatures')
  const credential = JSON.parse(credentialJson) as Record<string, unknown>
  const proof = credential.proof as Record<string, unknown>
  if (!proof) throw new Error('Credential missing proof field')
  if (proof.cryptosuite !== 'studentzk-bbs-2023') {
    throw new Error(`Wrong cryptosuite: expected "studentzk-bbs-2023", got "${String(proof.cryptosuite)}"`)
  }

  // Fetch issuer's public key from .well-known endpoint.
  const pubKeyUrl = new URL('/.well-known/studentzkp-bbs-key.json', issuerBaseUrl)
  const pubKeyRes = await fetch(pubKeyUrl.toString(), { credentials: 'omit' })
  if (!pubKeyRes.ok) {
    throw new Error(`Failed to fetch issuer BBS public key: ${pubKeyRes.status} ${pubKeyRes.statusText}`)
  }
  const pubKeyDoc = (await pubKeyRes.json()) as Record<string, unknown>
  const pubKeyB64 = pubKeyDoc.publicKey as string
  if (!pubKeyB64) throw new Error('Public key document missing publicKey field')
  const publicKeyBytes = base64UrlToBytes(pubKeyB64)

  // Extract proof value (signature).
  const proofValueB64 = proof.proofValue as string
  if (!proofValueB64) throw new Error('Proof missing proofValue')
  const proofBytes = base64UrlToBytes(proofValueB64)

  // Canonicalize revealed attributes (flat-leaf format).
  const credSubject = credential.credentialSubject as Record<string, unknown> | undefined
  if (!credSubject) throw new Error('Credential missing credentialSubject')
  const revealed = flattenObject(credSubject)
  const messages = Object.entries(revealed)
    .sort(([k1], [k2]) => k1.localeCompare(k2))
    .map(([k, v]) => `${k}=${v}`)

  // Verify BBS proof using @mattrglobal library.
  const proofRes = await verifyProof({
    proof: proofBytes,
    publicKey: publicKeyBytes,
    messages: messages.map((m) => textEncoder.encode(m)),
    nonce: new Uint8Array(),
  })
  if (!proofRes.verified) {
    throw new Error(`BBS-2023 proof verification failed: ${proofRes.error ?? 'unknown error'}`)
  }

  // Compute sha256 of proof for unlinkability comparison.
  const proofHash = await sha256Base64Url(proofValueB64)

  // Check revocation status.
  let revoked: boolean | null = null
  const status = (credential as { credentialStatus?: { idx?: number; uri?: string } })
    .credentialStatus
  if (status?.idx !== undefined && status?.uri) {
    try {
      const listRes = await fetch(status.uri, { credentials: 'omit' })
      if (listRes.ok) {
        const listJson = (await listRes.json()) as {
          status_list: { bits: number; lst: string }
        }
        const compressed = base64UrlToBytes(listJson.status_list.lst)
        const decompressed = pako.inflate(compressed)
        const byte = decompressed[status.idx >>> 3] ?? 0
        const bit = (byte >> (status.idx & 7)) & 1
        revoked = bit === 1
      }
    } catch {
      // Offline: leave revoked = null
    }
  }

  const validUntil = (credential as { validUntil?: string }).validUntil ?? null
  const issuer = String((credential as { issuer?: string }).issuer ?? '')
  const vct = String((credential as { credentialSubject?: { type?: string } }).credentialSubject?.type ?? '')

  return {
    format: 'bbs-2023',
    signatureValid: true,
    revoked,
    issuer,
    vct,
    validUntil,
    revealed,
    hiddenCount: 0,
    keyBinding: { state: 'absent' },
    proofHash,
    raw: {
      credential,
      proof,
    },
  }
}

/**
 * Verify a BBS+ selective-disclosure presentation.
 * The proof is already derived — verify directly against issuer public key.
 */
export async function verifyBbsSelectivePresentation(
  presentationJson: string,
  issuerBaseUrl: string,
): Promise<VerifyResult> {
  const { verifyProof } = await import('@mattrglobal/bbs-signatures')
  const presentation = JSON.parse(presentationJson) as Record<string, unknown>
  const proof = presentation.proof as Record<string, unknown>
  if (!proof) throw new Error('Presentation missing proof field')

  // Extract verification method to determine issuer key URL
  const verificationMethod = proof.verificationMethod as string
  let keyBaseUrl = issuerBaseUrl
  if (verificationMethod) {
    try {
      const url = new URL(verificationMethod)
      keyBaseUrl = `${url.protocol}//${url.host}`
    } catch { /* use issuerBaseUrl fallback */ }
  }

  // Fetch issuer's public key
  const pubKeyUrl = new URL('/.well-known/studentzkp-bbs-key.json', keyBaseUrl)
  const pubKeyRes = await fetch(pubKeyUrl.toString(), { credentials: 'omit' })
  if (!pubKeyRes.ok) {
    throw new Error(`Failed to fetch issuer BBS public key: ${pubKeyRes.status} ${pubKeyRes.statusText}`)
  }
  const pubKeyDoc = (await pubKeyRes.json()) as Record<string, unknown>
  const pubKeyB64 = pubKeyDoc.publicKey as string
  if (!pubKeyB64) throw new Error('Public key document missing publicKey field')
  const publicKeyBytes = base64UrlToBytes(pubKeyB64)

  // Extract proof components
  const derivedProofB64 = proof.derivedProofValue as string
  if (!derivedProofB64) throw new Error('Proof missing derivedProofValue')
  const derivedProofBytes = base64UrlToBytes(derivedProofB64)
  const disclosedMessages = proof.disclosedMessages as string[]
  const totalMessageCount = proof.totalMessageCount as number
  const nonce = proof.nonce as string ?? 'selective-disclosure'

  // Verify the derived BBS proof
  const proofRes = await verifyProof({
    proof: derivedProofBytes,
    publicKey: publicKeyBytes,
    messages: disclosedMessages.map((m) => textEncoder.encode(m)),
    nonce: textEncoder.encode(nonce),
  })
  if (!proofRes.verified) {
    throw new Error(`BBS-2023 selective-disclosure proof verification failed: ${proofRes.error ?? 'unknown error'}`)
  }

  // Parse disclosed attributes
  const revealed: Record<string, unknown> = {}
  for (const msg of disclosedMessages) {
    const eqIdx = msg.indexOf('=')
    if (eqIdx < 0) continue
    const key = msg.substring(0, eqIdx)
    const val_ = msg.substring(eqIdx + 1)
    // Strip the "credentialSubject." prefix for display
    const displayKey = key.startsWith('credentialSubject.') ? key.substring('credentialSubject.'.length) : key
    try { revealed[displayKey] = JSON.parse(val_) } catch { revealed[displayKey] = val_ }
  }

  const hiddenCount = totalMessageCount - disclosedMessages.length
  const proofHash = await sha256Base64Url(derivedProofB64)

  // Revocation check
  let revoked: boolean | null = null
  const status = presentation.credentialStatus as { statusListIndex?: number; statusListCredential?: string } | null
  if (status?.statusListIndex !== undefined && status?.statusListCredential) {
    try {
      const listRes = await fetch(status.statusListCredential, { credentials: 'omit' })
      if (listRes.ok) {
        const listJson = (await listRes.json()) as { status_list: { bits: number; lst: string } }
        const compressed = base64UrlToBytes(listJson.status_list.lst)
        const decompressed = pako.inflate(compressed)
        const byte = decompressed[status.statusListIndex >>> 3] ?? 0
        const bit = (byte >> (status.statusListIndex & 7)) & 1
        revoked = bit === 1
      }
    } catch { /* offline */ }
  }

  return {
    format: 'bbs-2023',
    signatureValid: true,
    revoked,
    issuer: String(presentation.issuer ?? ''),
    vct: '',
    validUntil: (presentation.validUntil as string) ?? null,
    revealed,
    hiddenCount,
    keyBinding: { state: 'absent' },
    proofHash,
    raw: { credential: presentation, proof },
  }
}

export async function verifySdJwtVc(
  compact: string,
  issuerBaseUrl: string,
  options: VerifyOptions = {},
): Promise<VerifyResult> {
  const parts = compact.split('~')
  if (parts.length < 2) throw new Error('Not an SD-JWT: no ~ separator found.')

  const jwt = parts[0]
  // Drop empty segments (presentations end with `~` so the last split element is "").
  const tail = parts.slice(1).filter((p) => p.length > 0)

  // Detect a trailing KB-JWT and split it off from the disclosure list.
  let kbJwt: string | null = null
  let disclosureB64s = tail
  if (tail.length > 0 && looksLikeKbJwt(tail[tail.length - 1])) {
    kbJwt = tail[tail.length - 1]
    disclosureB64s = tail.slice(0, -1)
  }

  const [headerB64] = jwt.split('.')
  const header = JSON.parse(base64UrlDecodeString(headerB64)) as Record<string, unknown>

  const jwksUrl = new URL('/.well-known/jwks.json', issuerBaseUrl)
  const jwks = createRemoteJWKSet(jwksUrl)
  const { payload } = await jwtVerify(jwt, jwks, {
    algorithms: [header.alg as string],
  })

  const sdHashes = new Set<string>((payload._sd as string[]) ?? [])
  const revealed: Record<string, unknown> = {}
  const rawDisclosures: Array<{ name: string; value: unknown }> = []

  for (const disclosureB64 of disclosureB64s) {
    const hash = await sha256Base64Url(disclosureB64)
    if (!sdHashes.has(hash)) {
      throw new Error(
        'Disclosure rejected: its hash is not present in the issuer-signed _sd array. ' +
          'The SD-JWT was tampered with or a disclosure was forged.',
      )
    }
    const [, name, value] = JSON.parse(base64UrlDecodeString(disclosureB64)) as [
      string,
      string,
      unknown,
    ]
    revealed[name] = value
    rawDisclosures.push({ name, value })
  }

  // Status List (draft-ietf-oauth-status-list) — fetch if referenced.
  let revoked: boolean | null = null
  const status = (payload as { status?: { status_list?: { idx: number; uri: string } } })
    .status?.status_list
  if (status) {
    try {
      const listRes = await fetch(status.uri, { credentials: 'omit' })
      if (listRes.ok) {
        const listJson = (await listRes.json()) as {
          status_list: { bits: number; lst: string }
        }
        const compressed = base64UrlToBytes(listJson.status_list.lst)
        const decompressed = pako.inflate(compressed)
        const byte = decompressed[status.idx >>> 3] ?? 0
        const bit = (byte >> (status.idx & 7)) & 1
        revoked = bit === 1
      }
    } catch {
      // Offline or failed fetch: leave revoked = null and surface a "stale" badge.
    }
  }

  // Key-Binding JWT (SD-JWT-VC §4.3) — proves the holder controls the StrongBox key
  // pinned in the credential's `cnf` claim. Replay protection: sd_hash binds the
  // signed assertion to the exact disclosure set just presented.
  const cnf = (payload as { cnf?: { jwk?: JWK } }).cnf
  const keyBinding = await verifyKeyBinding(
    kbJwt,
    cnf?.jwk,
    `${jwt}~${disclosureB64s.join('~')}${disclosureB64s.length ? '~' : ''}`,
    options,
  )

  const hiddenCount = sdHashes.size - rawDisclosures.length

  // Compute sha256 of proof for unlinkability comparison.
  const proofHash = await sha256Base64Url(jwt)

  return {
    format: 'sd-jwt-vc',
    signatureValid: true,
    revoked,
    issuer: String(payload.iss ?? ''),
    vct: String(payload.vct ?? ''),
    validUntil: (payload as { valid_until?: string }).valid_until ?? null,
    revealed,
    hiddenCount,
    keyBinding,
    proofHash,
    raw: {
      header,
      payload: payload as Record<string, unknown>,
      disclosures: rawDisclosures,
    },
  }
}

export function validateDcql(
  result: VerifyResult,
  dcqlRequest: DcqlRequest,
): { valid: boolean; errors: DcqlValidationError[] } {
  const errors: DcqlValidationError[] = []

  // Find a matching credential requirement in the DCQL request.
  // For now, use the first one (single-credential demo).
  const credReq = dcqlRequest.credentials[0]
  if (!credReq) {
    return { valid: false, errors: [{ constraint: 'dcql', detail: 'No credential requirements in DCQL' }] }
  }

  // Validate format.
  const acceptedFormats = Array.isArray(credReq.format) ? credReq.format : [credReq.format]
  const formatMap: Record<string, string> = {
    'sd-jwt-vc': 'vc+sd-jwt',
    'bbs-2023': 'vc+bbs',
  }
  const resultFormat = formatMap[result.format]
  if (!acceptedFormats.includes(resultFormat)) {
    errors.push({
      constraint: 'format',
      detail: `Credential format "${resultFormat}" not in accepted formats: ${acceptedFormats.join(', ')}`,
    })
  }

  // Validate credential type (vct).
  if (credReq.meta?.vct_values) {
    if (!credReq.meta.vct_values.includes(result.vct)) {
      errors.push({
        constraint: 'vct',
        detail: `Credential type "${result.vct}" not in accepted types: ${credReq.meta.vct_values.join(', ')}`,
      })
    }
  }

  // Validate that required claims are present and disclosed.
  if (credReq.claims) {
    for (const claim of credReq.claims) {
      const claimPath = claim.path.join('.')
      // For nested paths like ['age_equal_or_over'], check for that key
      // For paths like ['age_equal_or_over', '18'], flatten: age_equal_or_over.18
      if (!(claimPath in result.revealed)) {
        errors.push({
          constraint: 'claim',
          detail: `Required claim "${claimPath}" is not disclosed in the presentation`,
        })
      }
    }
  }

  return {
    valid: errors.length === 0,
    errors,
  }
}

export async function verifyPresentation(
  input: string,
  issuerBaseUrl: string,
  options: VerifyOptions = {},
  dcqlRequest?: DcqlRequest,
): Promise<VerifyResult> {
  const trimmed = input.trim()

  let result: VerifyResult
  // Detect format: SD-JWT-VC contains ~, BBS is JSON
  if (trimmed.includes('~')) {
    result = await verifySdJwtVc(trimmed, issuerBaseUrl, options)
  } else {
    try {
      const parsed = JSON.parse(trimmed)
      if (parsed.type === 'BbsSelectiveDisclosure') {
        result = await verifyBbsSelectivePresentation(trimmed, issuerBaseUrl)
      } else {
        result = await verifyBbsCredential(trimmed, issuerBaseUrl)
      }
    } catch (e) {
      throw new Error(
        `Failed to verify credential. Error: ${e instanceof Error ? e.message : String(e)}`
      )
    }
  }

  // Validate against DCQL if provided.
  if (dcqlRequest) {
    result.dcqlValidation = validateDcql(result, dcqlRequest)
  }

  return result
}

async function verifyKeyBinding(
  kbJwt: string | null,
  cnfJwk: JWK | undefined,
  presentationBody: string,
  options: VerifyOptions,
): Promise<KeyBindingResult> {
  if (!kbJwt) {
    if (cnfJwk && options.requireKeyBinding) {
      return {
        state: 'failed',
        reason:
          'Credential has a cnf claim (device-bound) but presentation is missing a KB-JWT. ' +
          'Holder must sign the verifier challenge with the StrongBox key.',
      }
    }
    return { state: 'absent' }
  }

  if (!cnfJwk) {
    return {
      state: 'failed',
      reason:
        'Presentation includes a KB-JWT, but the credential has no cnf claim — nothing to verify the binding signature against.',
    }
  }

  try {
    const [headerB64] = kbJwt.split('.')
    const header = JSON.parse(base64UrlDecodeString(headerB64)) as Record<string, unknown>
    if (header.typ !== 'kb+jwt') {
      throw new Error(`KB-JWT has wrong typ header: expected "kb+jwt", got "${String(header.typ)}"`)
    }
    if (header.alg !== 'ES256') {
      throw new Error(`KB-JWT alg must be ES256, got "${String(header.alg)}"`)
    }

    const key = await importJWK({ ...cnfJwk, alg: 'ES256' }, 'ES256')
    const { payload } = await jwtVerify(kbJwt, key, { algorithms: ['ES256'] })

    if (options.expectedAudience && payload.aud !== options.expectedAudience) {
      throw new Error(
        `KB-JWT audience mismatch: expected "${options.expectedAudience}", got "${String(payload.aud)}"`,
      )
    }
    if (options.expectedNonce && payload.nonce !== options.expectedNonce) {
      throw new Error('KB-JWT nonce mismatch (replay or wrong challenge).')
    }
    if (typeof payload.iat !== 'number') {
      throw new Error('KB-JWT missing iat.')
    }
    const skewSec = 300
    const nowSec = Math.floor(Date.now() / 1000)
    if (Math.abs(nowSec - payload.iat) > skewSec) {
      throw new Error(`KB-JWT iat is more than ${skewSec}s away from now.`)
    }

    const expectedSdHash = await sha256Base64Url(presentationBody)
    if (payload.sd_hash !== expectedSdHash) {
      throw new Error(
        'KB-JWT sd_hash does not match the presented disclosures. The presentation was tampered with after signing.',
      )
    }

    return {
      state: 'verified',
      cnfJwk,
      nonce: String(payload.nonce ?? ''),
      audience: String(payload.aud ?? ''),
      iat: payload.iat,
    }
  } catch (e) {
    return { state: 'failed', reason: e instanceof Error ? e.message : String(e) }
  }
}
