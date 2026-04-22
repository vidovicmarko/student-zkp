/// Generates a BBS+ key pair.
/// Returns `(public_key_bytes, secret_key_bytes)`.
pub fn bbs_keygen() -> (Vec<u8>, Vec<u8>) {
    todo!("Phase 2 — implement BBS+ key generation using dock_crypto")
}

/// Signs a set of messages with the given BBS+ secret key.
/// Returns the signature bytes.
pub fn bbs_sign(messages: &[Vec<u8>], secret_key: &[u8]) -> Vec<u8> {
    todo!("Phase 2 — implement BBS+ signing using dock_crypto")
}

/// Derives a selective-disclosure proof from an existing BBS+ signature.
/// Only the messages at `disclosed_indices` will be revealed to the verifier.
/// Returns the proof bytes.
pub fn bbs_derive_proof(
    signature: &[u8],
    messages: &[Vec<u8>],
    disclosed_indices: &[usize],
    nonce: &[u8],
) -> Vec<u8> {
    todo!("Phase 2 — implement BBS+ proof derivation using dock_crypto")
}

/// Verifies a BBS+ selective-disclosure proof.
/// Returns `true` if the proof is valid for the given disclosed messages.
pub fn bbs_verify_proof(
    proof: &[u8],
    public_key: &[u8],
    disclosed_messages: &[Vec<u8>],
    nonce: &[u8],
) -> bool {
    todo!("Phase 2 — implement BBS+ proof verification using dock_crypto")
}

#[cfg(test)]
mod tests {
    #[test]
    fn placeholder() {
        // TODO: Phase 2 — add round-trip keygen → sign → derive_proof → verify_proof test
    }
}
