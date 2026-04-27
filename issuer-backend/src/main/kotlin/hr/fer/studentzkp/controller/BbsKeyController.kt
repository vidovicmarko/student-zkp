package hr.fer.studentzkp.controller

import hr.fer.studentzkp.service.BbsIssuerKeyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// Public-key advertisement for the BBS+ issuer (final_plan §5.2 / W3C
// `bbs-2023`). Verifiers fetch this to learn the issuer's pubkey + the
// canonicalization used to derive BBS messages.
//
// Mirrors `/.well-known/jwks.json` for the ES256 SD-JWT-VC key. Kept under the
// same `/.well-known/` prefix so deployment-time URL conventions are unified.
@RestController
class BbsKeyController(
    private val keyService: BbsIssuerKeyService,
) {

    @GetMapping("/.well-known/studentzkp-bbs-key.json")
    fun bbsKeyDocument(): Map<String, Any> = keyService.publicKeyDocument()
}
