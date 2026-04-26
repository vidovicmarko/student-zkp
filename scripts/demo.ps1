# PowerShell-native equivalent of demo.sh — no bash, jq, or curl needed.
# End-to-end Phase 1 demo: ask the issuer for Ana Anic's Student credential,
# print the SD-JWT-VC + disclosures, and copy the SD-JWT to the clipboard.

[CmdletBinding()]
param(
    [string]$Issuer    = $(if ($env:ISSUER)    { $env:ISSUER }    else { 'http://localhost:8080' }),
    [string]$Verifier  = $(if ($env:VERIFIER)  { $env:VERIFIER }  else { 'http://localhost:5173' }),
    [string]$StudentId = $(if ($env:STUDENT_ID){ $env:STUDENT_ID }else { '0036123456' })
)

$ErrorActionPreference = 'Stop'

function Decode-B64Url {
    param([string]$Value)
    $s = $Value.Replace('-', '+').Replace('_', '/')
    $pad = (4 - $s.Length % 4) % 4
    if ($pad) { $s = $s + ('=' * $pad) }
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))
}

Write-Host "-> POST $Issuer/dev/credential/$StudentId"
$resp = Invoke-RestMethod -Method Post `
    -Uri "$Issuer/dev/credential/$StudentId" `
    -ContentType 'application/json' `
    -Body '{}'

""
"Credential ID : $($resp.credentialId)"
"Status index  : $($resp.statusIdx)"
""
"-- SD-JWT-VC (compact) -------------------------------------------"
$resp.sdJwt
"------------------------------------------------------------------"
""
"Disclosures (each is base64url(JSON [salt, name, value])):"
$resp.disclosures | ForEach-Object {
    $json = Decode-B64Url $_.disclosureB64
    "  - $json"
}

try {
    $resp.sdJwt | Set-Clipboard
    "`n(SD-JWT copied to clipboard)"
} catch {
    "`n(could not access clipboard: $($_.Exception.Message))"
}

""
"Next step:"
"  1. Open $Verifier in a browser."
"  2. Paste the SD-JWT-VC above into the textarea."
"  3. Click Verify. You should see is_student=true and age_equal_or_over.18=true."
