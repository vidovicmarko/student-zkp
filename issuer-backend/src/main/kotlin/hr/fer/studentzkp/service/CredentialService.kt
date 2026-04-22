package hr.fer.studentzkp.service

import hr.fer.studentzkp.model.Credential
import hr.fer.studentzkp.model.CredentialType
import java.util.UUID

// Credential-type-agnostic issuance surface (final_plan §5.9).
// Concrete implementations land in Phase 1 (SD-JWT-VC) and Phase 2 (BBS+).
interface CredentialService {

    // Phase 1 — SD-JWT-VC over OID4VCI (walt.id / eudi-lib-jvm-sdjwt-kt).
    fun issueSdJwtVc(
        type: CredentialType,
        subjectDid: String,
        attributes: Map<String, Any>,
        cnfKeyJwk: Map<String, Any>,
    ): Credential = TODO("Phase 1 — SD-JWT-VC issuance")

    // Phase 2 — W3C VCDM 2.0 with bbs-2023 cryptosuite (docknetwork/crypto via JNA).
    fun issueBbs(
        type: CredentialType,
        subjectDid: String,
        attributes: Map<String, Any>,
        cnfKeyJwk: Map<String, Any>,
    ): Credential = TODO("Phase 2 — BBS+ issuance")

    // Phase 3 — Revocation via IETF Token Status List bit flip.
    fun revoke(credentialId: UUID): Unit = TODO("Phase 3 — token status list revocation")
}
