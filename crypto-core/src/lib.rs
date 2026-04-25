//! StudentZK crypto core.
//!
//! BBS+ on BLS12-381 (signatures in G1, public keys in G2) wired through a
//! byte-oriented API so the same `.so` is consumed by:
//!   * the issuer JVM via JNA (see `BbsCryptoBridge.kt`),
//!   * the Android holder via UniFFI (see `studentzkp_crypto.udl`).
//!
//! The Rust surface is intentionally tiny — four functions for the BBS+ flow
//! plus accumulator stubs for Phase 3. All non-trivial crypto is delegated to
//! `bbs_plus` (audited, Apache-2.0, by docknetwork).
//!
//! Wire format conventions:
//!   * All public/secret/sig/proof bytes are `ark-serialize` *compressed*.
//!   * Each message is hashed-to-scalar with `MSG_DST` so callers may pass any
//!     bytes (UTF-8 attribute name + value works fine).
//!   * Challenges follow Fiat-Shamir: the protocol's `challenge_contribution`
//!     stream + revealed_msgs + verifier nonce, hashed-to-field with
//!     `CHALLENGE_DST`. Verifier independently recomputes the same challenge
//!     so it never has to leave the Rust side of the FFI.
//!
//! Spec context: final_plan §5.2 (Rust crypto sidecar), §4 (why BBS+),
//! IETF draft-irtf-cfrg-bbs-signatures, W3C `bbs-2023` cryptosuite.

#![allow(non_snake_case)]

use ark_bls12_381::{Bls12_381, Fr};
use ark_serialize::{CanonicalDeserialize, CanonicalSerialize};
use ark_std::{collections::BTreeMap, rand::rngs::OsRng, UniformRand};
use bbs_plus::{
    proof::{PoKOfSignatureG1Proof, PoKOfSignatureG1Protocol},
    setup::{PublicKeyG2, SecretKey, SignatureParamsG1},
    signature::SignatureG1,
};
use dock_crypto_utils::{hashing_utils::hash_to_field, signature::MessageOrBlinding};
use sha2::Sha256;

// ---------------------------------------------------------------------------
// Domain separation (ciphersuite-style). Changing any of these is a breaking
// change — every credential ever issued binds to these strings.
// ---------------------------------------------------------------------------
const PARAMS_LABEL: &[u8] = b"StudentZK-BBS-Params-v1";
const MSG_DST: &[u8] = b"StudentZK-BBS-Msg-v1";
const CHALLENGE_DST: &[u8] = b"StudentZK-BBS-Chal-v1";

type E = Bls12_381;

// ---------------------------------------------------------------------------
// BBS+ core (final_plan §5.2)
// ---------------------------------------------------------------------------

/// Generates a BBS+ keypair on BLS12-381.
/// Returns `(public_key_bytes, secret_key_bytes)` as ark-serialize compressed.
///
/// The public key lives in G2 and is independent of the message count of
/// future signatures — `g2` in our params is derived only from `PARAMS_LABEL`,
/// not from the message count, so one keypair signs creds of any size.
pub fn bbs_keygen() -> (Vec<u8>, Vec<u8>) {
    let mut rng = OsRng;
    let sk = SecretKey::<Fr>(Fr::rand(&mut rng));
    // Any message count works for the g2 derivation; pick 1 to keep the
    // params object small. (h_0, h_1, …) are unused for keygen.
    let params = derive_params(1);
    let pk = PublicKeyG2::<E>::generate_using_secret_key(&sk, &params);

    (compress(&pk), compress(&sk))
}

/// Signs a sequence of attribute messages with a BBS+ secret key.
/// Each `messages[i]` is hashed-to-scalar with `MSG_DST` before signing — this
/// matches the encoding used by `bbs_derive_proof` / `bbs_verify_proof`, so
/// callers can pass raw attribute bytes throughout.
pub fn bbs_sign(messages: &[Vec<u8>], secret_key: &[u8]) -> Result<Vec<u8>, String> {
    if messages.is_empty() {
        return Err("messages must not be empty".into());
    }
    let sk: SecretKey<Fr> = decompress(secret_key, "secret_key")?;
    let scalars = hash_messages(messages);
    let params = derive_params(messages.len());

    let mut rng = OsRng;
    let sig = SignatureG1::<E>::new(&mut rng, &scalars, &sk, &params)
        .map_err(|e| format!("bbs_sign failed: {e:?}"))?;
    Ok(compress(&sig))
}

/// Derives a selective-disclosure proof from an existing BBS+ signature.
///
/// `messages` is the full attribute list (same order as at signing time).
/// `disclosed_indices` is the subset to reveal — every other message is hidden
/// but its knowledge is proved. `nonce` binds the proof to the verifier's
/// challenge, preventing replay.
pub fn bbs_derive_proof(
    signature: &[u8],
    messages: &[Vec<u8>],
    disclosed_indices: &[usize],
    nonce: &[u8],
) -> Result<Vec<u8>, String> {
    if messages.is_empty() {
        return Err("messages must not be empty".into());
    }
    for &idx in disclosed_indices {
        if idx >= messages.len() {
            return Err(format!(
                "disclosed_indices contains {idx} but only {} messages",
                messages.len()
            ));
        }
    }
    let sig: SignatureG1<E> = decompress(signature, "signature")?;
    let scalars = hash_messages(messages);
    let params = derive_params(messages.len());
    let disclosed_set: ark_std::collections::BTreeSet<usize> =
        disclosed_indices.iter().copied().collect();

    // Build the protocol input: each message is either revealed or randomly
    // blinded. The crate computes blindings internally for us.
    let messages_and_blindings: Vec<MessageOrBlinding<Fr>> = scalars
        .iter()
        .enumerate()
        .map(|(i, m)| {
            if disclosed_set.contains(&i) {
                MessageOrBlinding::RevealMessage(m)
            } else {
                MessageOrBlinding::BlindMessageRandomly(m)
            }
        })
        .collect();

    let mut rng = OsRng;
    let protocol =
        PoKOfSignatureG1Protocol::<E>::init(&mut rng, &sig, &params, messages_and_blindings)
            .map_err(|e| format!("PoKOfSignatureG1Protocol::init failed: {e:?}"))?;

    let revealed_map: BTreeMap<usize, Fr> = disclosed_set
        .iter()
        .map(|&i| (i, scalars[i]))
        .collect();
    let challenge = compute_challenge(
        |w| protocol.challenge_contribution(&revealed_map, &params, w),
        &revealed_map,
        nonce,
    )?;

    let proof = protocol
        .gen_proof(&challenge)
        .map_err(|e| format!("PoKOfSignatureG1Protocol::gen_proof failed: {e:?}"))?;
    Ok(compress(&proof))
}

/// Verifies a BBS+ selective-disclosure proof against the issuer's public key.
///
/// Inputs mirror `bbs_derive_proof` from the verifier's side: the verifier
/// knows only the disclosed messages (and their indices) plus the total
/// message count of the original signed credential.
pub fn bbs_verify_proof(
    proof: &[u8],
    public_key: &[u8],
    disclosed_indices: &[usize],
    disclosed_messages: &[Vec<u8>],
    total_message_count: usize,
    nonce: &[u8],
) -> Result<bool, String> {
    if disclosed_indices.len() != disclosed_messages.len() {
        return Err("disclosed_indices and disclosed_messages length mismatch".into());
    }
    if total_message_count == 0 {
        return Err("total_message_count must be > 0".into());
    }
    for &idx in disclosed_indices {
        if idx >= total_message_count {
            return Err(format!(
                "disclosed_indices contains {idx} but total_message_count={total_message_count}"
            ));
        }
    }
    let pk: PublicKeyG2<E> = decompress(public_key, "public_key")?;
    let pkof: PoKOfSignatureG1Proof<E> = decompress(proof, "proof")?;
    let params = derive_params(total_message_count);

    let revealed_map: BTreeMap<usize, Fr> = disclosed_indices
        .iter()
        .copied()
        .zip(disclosed_messages.iter().map(|m| hash_message(m)))
        .collect();

    let challenge = compute_challenge(
        |w| pkof.challenge_contribution(&revealed_map, &params, w),
        &revealed_map,
        nonce,
    )?;

    Ok(pkof.verify(&revealed_map, &challenge, pk, params).is_ok())
}

// ---------------------------------------------------------------------------
// Accumulator revocation stubs (final_plan §5.5) — Phase 3.
// ---------------------------------------------------------------------------

/// Creates a fresh KB-accumulator for a credential type.
pub fn acc_create() -> Vec<u8> {
    todo!("Phase 3 — vb_accumulator::positive::PositiveAccumulator")
}

/// Adds a member (credential id) to the accumulator and returns the witness.
pub fn acc_add(_accumulator: &[u8], _member: &[u8]) -> (Vec<u8>, Vec<u8>) {
    todo!("Phase 3 — accumulator add + membership witness")
}

/// Removes a member (revocation) and publishes the update delta.
pub fn acc_remove(_accumulator: &[u8], _member: &[u8]) -> Vec<u8> {
    todo!("Phase 3 — accumulator remove")
}

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

fn derive_params(message_count: usize) -> SignatureParamsG1<E> {
    SignatureParamsG1::<E>::new::<Sha256>(PARAMS_LABEL, message_count as u32)
}

fn hash_message(bytes: &[u8]) -> Fr {
    hash_to_field::<Fr, Sha256>(MSG_DST, bytes)
}

fn hash_messages(messages: &[Vec<u8>]) -> Vec<Fr> {
    messages.iter().map(|m| hash_message(m)).collect()
}

fn compress<T: CanonicalSerialize>(value: &T) -> Vec<u8> {
    let mut buf = Vec::with_capacity(value.compressed_size());
    value
        .serialize_compressed(&mut buf)
        .expect("ark-serialize into Vec is infallible");
    buf
}

fn decompress<T: CanonicalDeserialize>(bytes: &[u8], what: &str) -> Result<T, String> {
    T::deserialize_compressed(bytes).map_err(|e| format!("decode {what} failed: {e:?}"))
}

fn compute_challenge<F>(
    write_contribution: F,
    revealed: &BTreeMap<usize, Fr>,
    nonce: &[u8],
) -> Result<Fr, String>
where
    F: FnOnce(&mut Vec<u8>) -> Result<(), bbs_plus::error::BBSPlusError>,
{
    let mut buf = Vec::with_capacity(256);
    write_contribution(&mut buf).map_err(|e| format!("challenge_contribution failed: {e:?}"))?;
    // Bind the verifier's nonce + the disclosed index set into the transcript.
    // (Index ordering is stable: BTreeMap iterates in sorted order.)
    for (idx, msg) in revealed {
        buf.extend_from_slice(&(*idx as u64).to_le_bytes());
        msg.serialize_compressed(&mut buf)
            .map_err(|e| format!("serialize revealed msg failed: {e:?}"))?;
    }
    buf.extend_from_slice(&(nonce.len() as u64).to_le_bytes());
    buf.extend_from_slice(nonce);
    Ok(hash_to_field::<Fr, Sha256>(CHALLENGE_DST, &buf))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture_messages() -> Vec<Vec<u8>> {
        vec![
            b"is_student=true".to_vec(),
            b"age_equal_or_over.18=true".to_vec(),
            b"university_id=fer.unizg.hr".to_vec(),
            b"given_name_hash=ee2a...".to_vec(),
            b"family_name_hash=9b1f...".to_vec(),
            b"student_id=0036123456".to_vec(),
        ]
    }

    #[test]
    fn signs_and_verifies_full_disclosure() {
        let (pk, sk) = bbs_keygen();
        let messages = fixture_messages();
        let sig = bbs_sign(&messages, &sk).expect("sign");

        // Disclose everything → still a valid proof, just zero-hidden.
        let all: Vec<usize> = (0..messages.len()).collect();
        let nonce = b"verifier-nonce-1";
        let proof = bbs_derive_proof(&sig, &messages, &all, nonce).expect("derive");

        let disclosed: Vec<Vec<u8>> = all.iter().map(|&i| messages[i].clone()).collect();
        assert!(bbs_verify_proof(&proof, &pk, &all, &disclosed, messages.len(), nonce).unwrap());
    }

    #[test]
    fn selective_disclosure_roundtrip() {
        let (pk, sk) = bbs_keygen();
        let messages = fixture_messages();
        let sig = bbs_sign(&messages, &sk).unwrap();

        // Disclose only "is_student" and "age >= 18". Hide the rest.
        let disclosed_idx = vec![0usize, 1];
        let nonce = b"verifier-nonce-discount-kiosk";
        let proof = bbs_derive_proof(&sig, &messages, &disclosed_idx, nonce).unwrap();

        let disclosed_msgs: Vec<Vec<u8>> =
            disclosed_idx.iter().map(|&i| messages[i].clone()).collect();
        let ok = bbs_verify_proof(
            &proof,
            &pk,
            &disclosed_idx,
            &disclosed_msgs,
            messages.len(),
            nonce,
        )
        .unwrap();
        assert!(ok, "expected proof to verify");
    }

    #[test]
    fn proof_rejected_with_wrong_nonce() {
        let (pk, sk) = bbs_keygen();
        let messages = fixture_messages();
        let sig = bbs_sign(&messages, &sk).unwrap();
        let proof = bbs_derive_proof(&sig, &messages, &[0, 1], b"nonce-A").unwrap();

        let disclosed: Vec<Vec<u8>> = [0usize, 1].iter().map(|&i| messages[i].clone()).collect();
        let ok =
            bbs_verify_proof(&proof, &pk, &[0, 1], &disclosed, messages.len(), b"nonce-B").unwrap();
        assert!(!ok, "proof tied to nonce-A must not verify under nonce-B");
    }

    #[test]
    fn proof_rejected_with_tampered_disclosed_message() {
        let (pk, sk) = bbs_keygen();
        let messages = fixture_messages();
        let sig = bbs_sign(&messages, &sk).unwrap();
        let proof = bbs_derive_proof(&sig, &messages, &[0, 1], b"n").unwrap();

        // Flip "true" → "fals" in the disclosed bytes; the verifier hashes the
        // tampered bytes to a different scalar than what was signed, so the
        // proof's commitments to the disclosed positions no longer reconcile.
        let tampered = b"is_student=fals".to_vec();
        let disclosed = vec![tampered, messages[1].clone()];

        let ok =
            bbs_verify_proof(&proof, &pk, &[0, 1], &disclosed, messages.len(), b"n").unwrap();
        assert!(!ok, "tampered disclosed message must invalidate the proof");
    }

    #[test]
    fn unlinkability_two_proofs_differ() {
        // BBS+ rerandomizes per derivation: the same signature → two distinct
        // proofs, demonstrating cryptographic unlinkability vs SD-JWT-VC's
        // identical issuer signature across presentations.
        let (_, sk) = bbs_keygen();
        let messages = fixture_messages();
        let sig = bbs_sign(&messages, &sk).unwrap();

        let p1 = bbs_derive_proof(&sig, &messages, &[0, 1], b"verifier-1").unwrap();
        let p2 = bbs_derive_proof(&sig, &messages, &[0, 1], b"verifier-2").unwrap();
        assert_ne!(p1, p2, "two proofs of the same sig must not be byte-equal");
    }
}
