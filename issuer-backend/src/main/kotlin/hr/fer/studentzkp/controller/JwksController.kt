package hr.fer.studentzkp.controller

import hr.fer.studentzkp.service.IssuerKeyService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// Public JWKS — verifiers fetch this to check SD-JWT-VC signatures.
// In production this should be cacheable (Cache-Control: public, max-age=3600)
// so verifiers can keep working against a warm cache when offline (§6.5).
@RestController
class JwksController(
    private val issuerKeyService: IssuerKeyService,
) {

    @GetMapping("/.well-known/jwks.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun jwks(): Map<String, Any> = issuerKeyService.publicJwkSet()
}
