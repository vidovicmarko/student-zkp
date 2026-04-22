// Hand-rolled SD-JWT-VC verification. Mirrors issuer-backend/src/.../SdJwtVcService.kt.
// Spec: https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/
// Kept dependency-light on purpose so this file is auditable end-to-end.
import { createRemoteJWKSet, jwtVerify } from 'jose'
import pako from 'pako'

export interface VerifyResult {
  signatureValid: boolean
  revoked: boolean | null
  issuer: string
  vct: string
  validUntil: string | null
  revealed: Record<string, unknown>
  hiddenCount: number
  raw: {
    header: Record<string, unknown>
    payload: Record<string, unknown>
    disclosures: Array<{ name: string; value: unknown }>
  }
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

export async function verifySdJwtVc(
  compact: string,
  issuerBaseUrl: string,
): Promise<VerifyResult> {
  const parts = compact.split('~')
  if (parts.length < 2) throw new Error('Not an SD-JWT: no ~ separator found.')

  const jwt = parts[0]
  // Trailing empty element from the final "~" — drop it, along with any empty strings.
  const disclosureB64s = parts.slice(1).filter((p) => p.length > 0)

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

  const hiddenCount = sdHashes.size - rawDisclosures.length

  return {
    signatureValid: true,
    revoked,
    issuer: String(payload.iss ?? ''),
    vct: String(payload.vct ?? ''),
    validUntil: (payload as { valid_until?: string }).valid_until ?? null,
    revealed,
    hiddenCount,
    raw: {
      header,
      payload: payload as Record<string, unknown>,
      disclosures: rawDisclosures,
    },
  }
}
