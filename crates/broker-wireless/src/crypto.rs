//! 配对链路需要的密码学原语。
//!
//! 只依赖 sha2 / aes-gcm 两个基础 crate：HKDF-SHA256 与 HMAC-SHA256 在此自行实现，
//! 避免 hkdf 0.13 拉取 digest 0.11 与工程使用的 sha2 0.10 (digest 0.10) 冲突。

use aes_gcm::aead::consts::U12;
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes128Gcm, Nonce};
use sha2::{Digest, Sha256, Sha512};

use crate::error::WirelessError;

/// HMAC-SHA256。标准实现：key 归一化到 64 字节块，ipad/opad 各做一次 SHA-256。
pub fn hmac_sha256(key: &[u8], data: &[u8]) -> [u8; 32] {
    const BLOCK: usize = 64;
    let mut k = [0u8; BLOCK];
    if key.len() > BLOCK {
        let d = Sha256::digest(key);
        k[..32].copy_from_slice(&d);
    } else {
        k[..key.len()].copy_from_slice(key);
    }
    let mut ipad = [0x36u8; BLOCK];
    let mut opad = [0x5cu8; BLOCK];
    for i in 0..BLOCK {
        ipad[i] ^= k[i];
        opad[i] ^= k[i];
    }
    let mut inner = Sha256::new();
    inner.update(ipad);
    inner.update(data);
    let ih = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(opad);
    outer.update(ih);
    let o = outer.finalize();
    let mut out = [0u8; 32];
    out.copy_from_slice(&o);
    out
}

/// RFC 5869 HKDF-SHA256。salt 为空时按标准使用 HashLen 个 0 字节。
pub fn hkdf_sha256(ikm: &[u8], salt: &[u8], info: &[u8], out_len: usize) -> Vec<u8> {
    let zero_salt = [0u8; 32];
    let s = if salt.is_empty() { &zero_salt[..] } else { salt };
    let prk = hmac_sha256(s, ikm);
    let mut okm = Vec::with_capacity(out_len);
    let mut t: Vec<u8> = Vec::new();
    let mut counter: u8 = 1;
    while okm.len() < out_len {
        let mut input = Vec::with_capacity(t.len() + info.len() + 1);
        input.extend_from_slice(&t);
        input.extend_from_slice(info);
        input.push(counter);
        t = hmac_sha256(&prk, &input).to_vec();
        okm.extend_from_slice(&t);
        counter = counter.wrapping_add(1);
    }
    okm.truncate(out_len);
    okm
}

pub fn sha512(data: &[u8]) -> [u8; 64] {
    let d = Sha512::digest(data);
    let mut out = [0u8; 64];
    out.copy_from_slice(&d);
    out
}

/// 带 8 字节小端长度前缀的 transcript 更新（与 ADB 配对服务端一致）。
pub fn update_with_length_prefix(h: &mut Sha512, data: &[u8]) {
    let len = data.len() as u64;
    h.update(len.to_le_bytes());
    h.update(data);
}

pub const HKDF_INFO: &[u8] = b"adb pairing_auth aes-128-gcm key";
pub const AES_KEY_LEN: usize = 16;

/// AES-128-GCM 序列密码上下文。nonce 为 12 字节：前 8 字节是小端 u64 序号，后 4 字节 0。
/// 加密与解密各自维护独立计数，从 0 开始，与 adb_pairing.cpp 的 enc/dec_sequence 一致。
pub struct GcmCipher {
    cipher: Aes128Gcm,
    enc_sequence: u64,
    dec_sequence: u64,
}

impl GcmCipher {
    pub fn new(key_material: &[u8]) -> Result<Self, WirelessError> {
        let key = hkdf_sha256(key_material, &[], HKDF_INFO, AES_KEY_LEN);
        if key.len() != AES_KEY_LEN {
            return Err(WirelessError::Crypto("HKDF 输出长度不足".into()));
        }
        let cipher = Aes128Gcm::new_from_slice(&key)
            .map_err(|e| WirelessError::Crypto(format!("AES 初始化失败: {e}")))?;
        Ok(Self { cipher, enc_sequence: 0, dec_sequence: 0 })
    }

    fn nonce(seq: u64) -> [u8; 12] {
        let mut n = [0u8; 12];
        n[..8].copy_from_slice(&seq.to_le_bytes());
        n
    }

    /// 输出 = ciphertext || 16-byte tag。
    pub fn seal(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, WirelessError> {
        let n = Self::nonce(self.enc_sequence);
        let mut nonce = Nonce::<U12>::default();
        nonce.copy_from_slice(&n);
        let out = self.cipher
            .encrypt(&nonce, plaintext)
            .map_err(|_| WirelessError::Crypto("AES-GCM 加密失败".into()))?;
        self.enc_sequence = self.enc_sequence.wrapping_add(1);
        Ok(out)
    }

    /// 解密失败（配对码错误）映射为 BadPairingCode，便于 UI 精确提示。
    pub fn open(&mut self, ciphertext_and_tag: &[u8]) -> Result<Vec<u8>, WirelessError> {
        let n = Self::nonce(self.dec_sequence);
        let mut nonce = Nonce::<U12>::default();
        nonce.copy_from_slice(&n);
        let out = self.cipher
            .decrypt(&nonce, ciphertext_and_tag)
            .map_err(|_| WirelessError::BadPairingCode)?;
        self.dec_sequence = self.dec_sequence.wrapping_add(1);
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // RFC 5869 Test Case 1
    #[test]
    fn hkdf_matches_rfc5869_case1() {
        let ikm = [0x0bu8; 22];
        let salt: [u8; 13] = [
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
        ];
        let info: [u8; 10] = [0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9];
        let okm = hkdf_sha256(&ikm, &salt, &info, 42);
        let expected = hex::decode(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        ).unwrap();
        assert_eq!(okm, expected);
    }

    #[test]
    fn gcm_nonce_sequence_is_independent_and_roundtrips() {
        let mut a = GcmCipher::new(&[0x42u8; 64]).unwrap();
        let msg = b"peer-info-8192".to_vec();
        let ct = a.seal(&msg).unwrap();
        assert_eq!(ct.len(), msg.len() + 16);
        let mut b = GcmCipher::new(&[0x42u8; 64]).unwrap();
        assert_eq!(b.open(&ct).unwrap(), msg);
        // 第二帧序号递增，nonce 不同
        let ct2 = a.seal(&msg).unwrap();
        assert_ne!(ct, ct2);
        assert_eq!(b.open(&ct2).unwrap(), msg);
    }

    #[test]
    fn wrong_key_material_fails_to_open() {
        let mut a = GcmCipher::new(&[1u8; 64]).unwrap();
        let ct = a.seal(b"payload").unwrap();
        let mut b = GcmCipher::new(&[2u8; 64]).unwrap();
        assert!(matches!(b.open(&ct), Err(WirelessError::BadPairingCode)));
    }
}
