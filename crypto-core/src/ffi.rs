//! C-ABI shim around the BBS+ functions in `lib.rs`.
//!
//! This is the layer JNA (issuer JVM) and the Android holder consume. The
//! pure-Rust functions return `Result<Vec<u8>, String>`; that doesn't survive
//! the C ABI, so each is wrapped here as `extern "C"` with:
//!
//!   * raw pointers + lengths for byte inputs (caller-owned),
//!   * a small `ByteBuf` struct for outputs (library-owned, freed via
//!     `studentzkp_buf_free`),
//!   * an `i32` status code,
//!   * a thread-local last-error string retrievable via `studentzkp_last_error`.
//!
//! No allocation crosses the boundary in either direction without an explicit
//! free call. The library never frees memory it didn't allocate, and the caller
//! never frees memory it didn't allocate. ByteBuf carries `cap` so we can
//! reconstruct the original `Vec<u8>` for `Vec::from_raw_parts`.
//!
//! Wire-level invariants:
//!   * All `Status::Ok` return values guarantee `out_*` slots are filled.
//!   * On any non-zero status, `out_*` slots are left zeroed; call
//!     `studentzkp_last_error` to fetch a UTF-8 description.
//!   * `studentzkp_buf_free(NULL)` and double-free are safe (idempotent).

use std::cell::RefCell;
use std::slice;

use crate::{bbs_derive_proof, bbs_keygen, bbs_sign, bbs_verify_proof};

// ---------------------------------------------------------------------------
// Status codes (kept stable — JNA/UniFFI bindings hard-code these).
// ---------------------------------------------------------------------------

pub const STUDENTZKP_OK: i32 = 0;
pub const STUDENTZKP_ERR_NULL_POINTER: i32 = 1;
pub const STUDENTZKP_ERR_INVALID_INPUT: i32 = 2;
pub const STUDENTZKP_ERR_CRYPTO: i32 = 3;
pub const STUDENTZKP_ERR_INTERNAL: i32 = 4;

// ---------------------------------------------------------------------------
// FFI types
// ---------------------------------------------------------------------------

/// A library-owned heap buffer. Caller must call `studentzkp_buf_free` exactly
/// once. `cap` is the original `Vec` capacity so the lib can reconstruct it.
#[repr(C)]
pub struct ByteBuf {
    pub ptr: *mut u8,
    pub len: usize,
    pub cap: usize,
}

impl ByteBuf {
    fn empty() -> Self {
        Self {
            ptr: std::ptr::null_mut(),
            len: 0,
            cap: 0,
        }
    }

    fn from_vec(mut v: Vec<u8>) -> Self {
        let ptr = v.as_mut_ptr();
        let len = v.len();
        let cap = v.capacity();
        std::mem::forget(v);
        Self { ptr, len, cap }
    }
}

/// A caller-owned read-only byte view. Lifetime is the duration of one call.
#[repr(C)]
pub struct ByteSlice {
    pub ptr: *const u8,
    pub len: usize,
}

impl ByteSlice {
    /// SAFETY: caller must ensure `ptr` is valid for `len` bytes for the
    /// duration of the borrow. Empty slices (`ptr=NULL, len=0`) are accepted.
    unsafe fn as_slice<'a>(&self) -> Result<&'a [u8], &'static str> {
        if self.len == 0 {
            return Ok(&[]);
        }
        if self.ptr.is_null() {
            return Err("byte slice has null ptr but non-zero len");
        }
        Ok(slice::from_raw_parts(self.ptr, self.len))
    }
}

// ---------------------------------------------------------------------------
// Thread-local last-error
// ---------------------------------------------------------------------------

thread_local! {
    static LAST_ERROR: RefCell<Option<String>> = const { RefCell::new(None) };
}

fn set_last_error(msg: impl Into<String>) {
    LAST_ERROR.with(|cell| *cell.borrow_mut() = Some(msg.into()));
}

fn clear_last_error() {
    LAST_ERROR.with(|cell| *cell.borrow_mut() = None);
}

/// Copies the current thread's last error message (if any) into `out_err`.
/// Returns `STUDENTZKP_OK` on success; `out_err.len == 0` means no error is
/// stored. After a successful call, the error is left in place (idempotent).
#[no_mangle]
pub unsafe extern "C" fn studentzkp_last_error(out_err: *mut ByteBuf) -> i32 {
    if out_err.is_null() {
        return STUDENTZKP_ERR_NULL_POINTER;
    }
    let msg = LAST_ERROR.with(|cell| cell.borrow().clone());
    let buf = match msg {
        Some(s) => ByteBuf::from_vec(s.into_bytes()),
        None => ByteBuf::empty(),
    };
    *out_err = buf;
    STUDENTZKP_OK
}

/// Free a library-allocated `ByteBuf`. Safe to call with a zeroed buffer or a
/// NULL pointer — both are no-ops.
#[no_mangle]
pub unsafe extern "C" fn studentzkp_buf_free(buf: *mut ByteBuf) {
    if buf.is_null() {
        return;
    }
    let b = &mut *buf;
    if !b.ptr.is_null() && b.cap > 0 {
        // Reconstitute the Vec so its allocator hooks run on drop.
        drop(Vec::from_raw_parts(b.ptr, b.len, b.cap));
    }
    b.ptr = std::ptr::null_mut();
    b.len = 0;
    b.cap = 0;
}

// ---------------------------------------------------------------------------
// Helpers to read array-of-slices and array-of-usize inputs
// ---------------------------------------------------------------------------

unsafe fn read_messages(
    ptr: *const ByteSlice,
    count: usize,
) -> Result<Vec<Vec<u8>>, &'static str> {
    if count == 0 {
        return Ok(Vec::new());
    }
    if ptr.is_null() {
        return Err("messages array pointer is null but count > 0");
    }
    let slices = slice::from_raw_parts(ptr, count);
    let mut out = Vec::with_capacity(count);
    for s in slices {
        let bytes = s.as_slice()?;
        out.push(bytes.to_vec());
    }
    Ok(out)
}

unsafe fn read_indices(ptr: *const usize, count: usize) -> Result<Vec<usize>, &'static str> {
    if count == 0 {
        return Ok(Vec::new());
    }
    if ptr.is_null() {
        return Err("indices array pointer is null but count > 0");
    }
    Ok(slice::from_raw_parts(ptr, count).to_vec())
}

// ---------------------------------------------------------------------------
// Public BBS+ entry points
// ---------------------------------------------------------------------------

/// Generates a fresh BBS+ keypair on BLS12-381.
/// Output: ark-serialize compressed public key (G2) and secret key (Fr scalar).
#[no_mangle]
pub unsafe extern "C" fn studentzkp_bbs_keygen(
    out_public_key: *mut ByteBuf,
    out_secret_key: *mut ByteBuf,
) -> i32 {
    if out_public_key.is_null() || out_secret_key.is_null() {
        return STUDENTZKP_ERR_NULL_POINTER;
    }
    clear_last_error();
    *out_public_key = ByteBuf::empty();
    *out_secret_key = ByteBuf::empty();

    let result = std::panic::catch_unwind(bbs_keygen);
    match result {
        Ok((pk, sk)) => {
            *out_public_key = ByteBuf::from_vec(pk);
            *out_secret_key = ByteBuf::from_vec(sk);
            STUDENTZKP_OK
        }
        Err(_) => {
            set_last_error("bbs_keygen panicked");
            STUDENTZKP_ERR_INTERNAL
        }
    }
}

/// Signs a sequence of attribute messages with a BBS+ secret key.
#[no_mangle]
pub unsafe extern "C" fn studentzkp_bbs_sign(
    messages: *const ByteSlice,
    messages_count: usize,
    secret_key: ByteSlice,
    out_signature: *mut ByteBuf,
) -> i32 {
    if out_signature.is_null() {
        return STUDENTZKP_ERR_NULL_POINTER;
    }
    clear_last_error();
    *out_signature = ByteBuf::empty();

    let messages = match read_messages(messages, messages_count) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };
    let sk = match secret_key.as_slice() {
        Ok(s) => s,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };

    let result = std::panic::catch_unwind(|| bbs_sign(&messages, sk));
    match result {
        Ok(Ok(sig)) => {
            *out_signature = ByteBuf::from_vec(sig);
            STUDENTZKP_OK
        }
        Ok(Err(e)) => {
            set_last_error(e);
            STUDENTZKP_ERR_CRYPTO
        }
        Err(_) => {
            set_last_error("bbs_sign panicked");
            STUDENTZKP_ERR_INTERNAL
        }
    }
}

/// Derives a selective-disclosure proof from an existing BBS+ signature.
#[no_mangle]
pub unsafe extern "C" fn studentzkp_bbs_derive_proof(
    signature: ByteSlice,
    messages: *const ByteSlice,
    messages_count: usize,
    disclosed_indices: *const usize,
    disclosed_count: usize,
    nonce: ByteSlice,
    out_proof: *mut ByteBuf,
) -> i32 {
    if out_proof.is_null() {
        return STUDENTZKP_ERR_NULL_POINTER;
    }
    clear_last_error();
    *out_proof = ByteBuf::empty();

    let messages_v = match read_messages(messages, messages_count) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };
    let indices_v = match read_indices(disclosed_indices, disclosed_count) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };
    let sig = match signature.as_slice() {
        Ok(s) => s,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };
    let nonce_slice = match nonce.as_slice() {
        Ok(s) => s,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };

    let result = std::panic::catch_unwind(|| {
        bbs_derive_proof(sig, &messages_v, &indices_v, nonce_slice)
    });
    match result {
        Ok(Ok(proof)) => {
            *out_proof = ByteBuf::from_vec(proof);
            STUDENTZKP_OK
        }
        Ok(Err(e)) => {
            set_last_error(e);
            STUDENTZKP_ERR_CRYPTO
        }
        Err(_) => {
            set_last_error("bbs_derive_proof panicked");
            STUDENTZKP_ERR_INTERNAL
        }
    }
}

/// Verifies a BBS+ selective-disclosure proof. Writes 1/0 into `out_valid`.
#[no_mangle]
pub unsafe extern "C" fn studentzkp_bbs_verify_proof(
    proof: ByteSlice,
    public_key: ByteSlice,
    disclosed_indices: *const usize,
    disclosed_count: usize,
    disclosed_messages: *const ByteSlice,
    disclosed_messages_count: usize,
    total_message_count: usize,
    nonce: ByteSlice,
    out_valid: *mut u8,
) -> i32 {
    if out_valid.is_null() {
        return STUDENTZKP_ERR_NULL_POINTER;
    }
    clear_last_error();
    *out_valid = 0;

    let proof_b = match proof.as_slice() {
        Ok(s) => s,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };
    let pk_b = match public_key.as_slice() {
        Ok(s) => s,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };
    let indices_v = match read_indices(disclosed_indices, disclosed_count) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };
    let messages_v = match read_messages(disclosed_messages, disclosed_messages_count) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };
    let nonce_slice = match nonce.as_slice() {
        Ok(s) => s,
        Err(e) => {
            set_last_error(e);
            return STUDENTZKP_ERR_INVALID_INPUT;
        }
    };

    let result = std::panic::catch_unwind(|| {
        bbs_verify_proof(
            proof_b,
            pk_b,
            &indices_v,
            &messages_v,
            total_message_count,
            nonce_slice,
        )
    });
    match result {
        Ok(Ok(valid)) => {
            *out_valid = if valid { 1 } else { 0 };
            STUDENTZKP_OK
        }
        Ok(Err(e)) => {
            set_last_error(e);
            STUDENTZKP_ERR_CRYPTO
        }
        Err(_) => {
            set_last_error("bbs_verify_proof panicked");
            STUDENTZKP_ERR_INTERNAL
        }
    }
}

// ---------------------------------------------------------------------------
// Tests — exercise the FFI surface end-to-end so a regression here breaks the
// build, not the consuming Android/JVM bindings.
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture_messages() -> Vec<Vec<u8>> {
        vec![
            b"is_student=true".to_vec(),
            b"age_equal_or_over.18=true".to_vec(),
            b"university_id=fer.unizg.hr".to_vec(),
        ]
    }

    fn slices_of(msgs: &[Vec<u8>]) -> Vec<ByteSlice> {
        msgs.iter()
            .map(|m| ByteSlice {
                ptr: m.as_ptr(),
                len: m.len(),
            })
            .collect()
    }

    fn slice_of(b: &[u8]) -> ByteSlice {
        ByteSlice {
            ptr: b.as_ptr(),
            len: b.len(),
        }
    }

    fn buf_to_vec(buf: &ByteBuf) -> Vec<u8> {
        unsafe { slice::from_raw_parts(buf.ptr, buf.len).to_vec() }
    }

    #[test]
    fn ffi_full_roundtrip() {
        unsafe {
            let mut pk = ByteBuf::empty();
            let mut sk = ByteBuf::empty();
            assert_eq!(studentzkp_bbs_keygen(&mut pk, &mut sk), STUDENTZKP_OK);
            assert!(pk.len > 0 && sk.len > 0);

            let messages = fixture_messages();
            let m_slices = slices_of(&messages);
            let mut sig = ByteBuf::empty();
            assert_eq!(
                studentzkp_bbs_sign(
                    m_slices.as_ptr(),
                    m_slices.len(),
                    slice_of(&buf_to_vec(&sk)),
                    &mut sig,
                ),
                STUDENTZKP_OK,
            );

            let disclosed = [0usize, 1];
            let nonce = b"verifier-nonce";
            let mut proof = ByteBuf::empty();
            assert_eq!(
                studentzkp_bbs_derive_proof(
                    slice_of(&buf_to_vec(&sig)),
                    m_slices.as_ptr(),
                    m_slices.len(),
                    disclosed.as_ptr(),
                    disclosed.len(),
                    slice_of(nonce),
                    &mut proof,
                ),
                STUDENTZKP_OK,
            );

            let disclosed_msgs: Vec<Vec<u8>> =
                disclosed.iter().map(|&i| messages[i].clone()).collect();
            let dm_slices = slices_of(&disclosed_msgs);
            let mut valid: u8 = 0;
            assert_eq!(
                studentzkp_bbs_verify_proof(
                    slice_of(&buf_to_vec(&proof)),
                    slice_of(&buf_to_vec(&pk)),
                    disclosed.as_ptr(),
                    disclosed.len(),
                    dm_slices.as_ptr(),
                    dm_slices.len(),
                    messages.len(),
                    slice_of(nonce),
                    &mut valid,
                ),
                STUDENTZKP_OK,
            );
            assert_eq!(valid, 1);

            // Wrong nonce → 0.
            let mut valid2: u8 = 0;
            assert_eq!(
                studentzkp_bbs_verify_proof(
                    slice_of(&buf_to_vec(&proof)),
                    slice_of(&buf_to_vec(&pk)),
                    disclosed.as_ptr(),
                    disclosed.len(),
                    dm_slices.as_ptr(),
                    dm_slices.len(),
                    messages.len(),
                    slice_of(b"different-nonce"),
                    &mut valid2,
                ),
                STUDENTZKP_OK,
            );
            assert_eq!(valid2, 0);

            studentzkp_buf_free(&mut pk);
            studentzkp_buf_free(&mut sk);
            studentzkp_buf_free(&mut sig);
            studentzkp_buf_free(&mut proof);
            // Idempotent: second free is a no-op.
            studentzkp_buf_free(&mut pk);
        }
    }

    #[test]
    fn last_error_is_set_on_invalid_input() {
        unsafe {
            // Empty messages → bbs_sign returns Err("messages must not be empty")
            let dummy_sk = vec![0u8; 32];
            let mut sig = ByteBuf::empty();
            let status = studentzkp_bbs_sign(
                std::ptr::null(),
                0,
                slice_of(&dummy_sk),
                &mut sig,
            );
            assert_ne!(status, STUDENTZKP_OK);

            let mut err = ByteBuf::empty();
            assert_eq!(studentzkp_last_error(&mut err), STUDENTZKP_OK);
            let msg = String::from_utf8(buf_to_vec(&err)).unwrap();
            assert!(!msg.is_empty(), "expected non-empty error message");
            studentzkp_buf_free(&mut err);
        }
    }

    #[test]
    fn null_out_param_returns_null_pointer_error() {
        unsafe {
            let status = studentzkp_bbs_keygen(std::ptr::null_mut(), std::ptr::null_mut());
            assert_eq!(status, STUDENTZKP_ERR_NULL_POINTER);
        }
    }
}
