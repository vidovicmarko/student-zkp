// Hand-rolled SD-JWT-VC verification. Mirrors issuer-backend/src/.../SdJwtVcService.kt.
// Spec: https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/
// Kept dependency-light on purpose so this file is auditable end-to-end.
import { createRemoteJWKSet, importJWK, jwtVerify, type JWK } from 'jose'
import pako from 'pako'

export interface VerifyResult {
  signatureValid: boolean
  revoked: boolean | null
  issuer: string
  vct: string
  validUntil: string | null
  revealed: Record<string, unknown>
  hiddenCount: number
  keyBinding: KeyBindingResult
  raw: {
    header: Record<string, unknown>
    payload: Record<string, unknown>
    disclosures: Array<{ name: string; value: unknown }>
  }
}

export type KeyBindingResult =
  | { state: 'absent' }
  | { state: 'verified'; cnfJwk: JWK; nonce: string; audience: string; iat: number }
  | { state: 'failed'; reason: string }

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

  return {
    signatureValid: true,
    revoked,
    issuer: String(payload.iss ?? ''),
    vct: String(payload.vct ?? ''),
    validUntil: (payload as { valid_until?: string }).valid_until ?? null,
    revealed,
    hiddenCount,
    keyBinding,
    raw: {
      header,
      payload: payload as Record<string, unknown>,
      disclosures: rawDisclosures,
    },
  }
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
