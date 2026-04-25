package hr.fer.studentzkp.controller

import hr.fer.studentzkp.service.Oid4VciService
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// Dev shortcut: kick off an OID4VCI flow for a known student. In production an
// admin UI calls Oid4VciService.createOffer when a student requests a wallet-
// addressable credential. Active only under the "dev-shortcut" Spring profile.
@RestController
@Profile("dev-shortcut")
class DevCredentialOfferController(
    private val oid4Vci: Oid4VciService,
) {

    @PostMapping("/dev/credential-offer/{studentId}")
    fun create(@PathVariable studentId: String): Map<String, Any> {
        val offer = oid4Vci.createOffer(studentId)
        val offerUri = "${oid4Vci.publicBaseUrl}/credential-offer/${offer.offerId}"
        // openid-credential-offer:// is the well-known scheme registered by
        // OID4VCI §4.1.3 for wallet deep-link delivery.
        val deepLink = "openid-credential-offer://?credential_offer_uri=" +
            URLEncoder.encode(offerUri, StandardCharsets.UTF_8)
        return mapOf(
            "offer_id" to offer.offerId,
            "pre_authorized_code" to offer.preAuthCode,
            "credential_offer_uri" to offerUri,
            "deep_link" to deepLink,
            "expires_in_seconds" to java.time.Duration
                .between(java.time.Instant.now(), offer.expiresAt).seconds,
        )
    }
}
