//! StudentZK crypto core.
//!
//! The full API surface (final_plan §5.2) — four BBS+ primitives plus
//! accumulator-based revocation stubs. Consumed by:
//!   * issuer-backend via JNA (see BbsCryptoBridge.kt)
//!   * holder-android via UniFFI (see studentzkp_crypto.udl)
//!
//! All bodies are `todo!()` until Phase 2. The API shape is stable; Phase 2
//! only fills in the implementations using `docknetwork/crypto`.

// ---------------------------------------------------------------------------
// BBS+ core (final_plan §5.2)
// ---------------------------------------------------------------------------

/// Generates a BBS+ keypair on BLS12-381.
/// Returns `(public_key_bytes, secret_key_bytes)`.
pub fn bbs_keygen() -> (Vec<u8>, Vec<u8>) {
    todo!("Phase 2 — dock_crypto::bbs_plus::setup::KeypairG2::generate_using_rng")
}

/// Signs a sequence of attribute messages with a BBS+ secret key.
/// Used by the issuer to emit a `bbs-2023` VC.
pub fn bbs_sign(messages: &[Vec<u8>], secret_key: &[u8]) -> Vec<u8> {
    todo!("Phase 2 — dock_crypto::bbs_plus::signature::SignatureG1::new")
}

/// Derives a selective-disclosure proof from an existing BBS+ signature.
/// Only the messages at `disclosed_indices` are revealed; all others remain
/// hidden but their knowledge is proved. `nonce` binds the proof to the
/// verifier's challenge, preventing replay.
pub fn bbs_derive_proof(
    signature: &[u8],
    messages: &[Vec<u8>],
    disclosed_indices: &[usize],
    nonce: &[u8],
) -> Vec<u8> {
    todo!("Phase 2 — dock_crypto::bbs_plus::proof::PoKOfSignatureG1Proof")
}

/// Verifies a BBS+ selective-disclosure proof against the issuer's public key.
pub fn bbs_verify_proof(
    proof: &[u8],
    public_key: &[u8],
    disclosed_messages: &[Vec<u8>],
    nonce: &[u8],
) -> bool {
    todo!("Phase 2 — dock_crypto verify_with_randomized_pairing_checker")
}

// ---------------------------------------------------------------------------
// Accumulator revocation stubs (final_plan §5.5) — Phase 3.
// Optional advanced story: proves non-revocation in zero knowledge, no status
// list fetch required at verification time.
// ---------------------------------------------------------------------------

/// Creates a fresh KB-accumulator for a credential type.
pub fn acc_create() -> Vec<u8> {
    todo!("Phase 3 — dock_crypto::vb_accumulator::positive::PositiveAccumulator")
}

/// Adds a member (credential id) to the accumulator and returns the witness.
pub fn acc_add(accumulator: &[u8], member: &[u8]) -> (Vec<u8>, Vec<u8>) {
    todo!("Phase 3 — accumulator add + membership witness")
}

/// Removes a member (revocation) and publishes the update delta.
pub fn acc_remove(accumulator: &[u8], member: &[u8]) -> Vec<u8> {
    todo!("Phase 3 — accumulator remove")
}

#[cfg(test)]
mod tests {
    #[test]
    fn placeholder_roundtrip() {
        // TODO Phase 2 — integration test:
        //   let (pk, sk) = bbs_keygen();
        //   let sig = bbs_sign(&messages, &sk);
        //   let proof = bbs_derive_proof(&sig, &messages, &[1, 3], &nonce);
        //   assert!(bbs_verify_proof(&proof, &pk, &disclosed, &nonce));
    }
}
