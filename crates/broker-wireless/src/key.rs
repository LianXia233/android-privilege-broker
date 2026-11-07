//! 客户端 ADB 密钥：RSA 2048、ADB 公钥结构编码、TLS 客户端证书与认证签名。
//!
//! 私钥以 PKCS#8 DER 保存在应用私有目录，配对注册与后续 TLS 连接复用同一把密钥；
//! 应用备份规则中排除该文件，避免密钥随备份外泄。

use std::fs;
use std::path::Path;
use std::sync::Arc;

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use rsa::pkcs1v15::Pkcs1v15Sign;
use rsa::pkcs8::{DecodePrivateKey, EncodePrivateKey};
use rsa::traits::PublicKeyParts;
use rsa::{BigUint, RsaPrivateKey};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, UnixTime};
use rustls::DigitallySignedStruct;

use crate::error::WirelessError;

pub const ADB_PUBKEY_MODULUS_SIZE: usize = 2048 / 8;
pub const ADB_PUBKEY_MODULUS_SIZE_WORDS: u32 = (ADB_PUBKEY_MODULUS_SIZE / 4) as u32;
pub const RSA_PUBLIC_KEY_SIZE: usize = 524;
pub const PEER_INFO_SIZE: usize = 8192;

/// 追加在 ADB 公钥 base64 之后的标识（含前导空格与结尾 NUL）。
const PUBKEY_NAME: &[u8] = b"privbroker";

/// SHA-1 DigestInfo 前缀，ADB 认证签名把 20 字节 token 当作「摘要」直接封装。
const DIGEST_INFO_SHA1: &[u8] = &[
    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
];

pub struct AdbKey {
    key: RsaPrivateKey,
    pkcs8_der: Vec<u8>,
    cert_der: Vec<u8>,
    pubkey_bytes: Vec<u8>,
}

/// 配对服务端使用自签证书，客户端不校验对端证书链（TLS exporter 与配对码提供绑定）。
#[derive(Debug)]
struct TrustAllVerifier;

impl ServerCertVerifier for TrustAllVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        // 设备端证书可能是 RSA 或 ECDSA，这里声明 provider 支持的全部方案，
        // 避免服务端因签名算法不匹配而中止握手。
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
            .to_vec()
    }
}

fn left_pad_to(bytes: &[u8], len: usize) -> Vec<u8> {
    let mut out = vec![0u8; len];
    let n = bytes.len().min(len);
    out[..n].copy_from_slice(&bytes[..n]);
    out
}

/// 求奇数 n 模 2^32 的乘法逆元（Newton 迭代，5 轮达到 32 位精度）。
fn inv_mod_2_32(n: u32) -> u32 {
    let n64 = n as u64;
    let mut x: u64 = 1;
    for _ in 0..5 {
        x = x.wrapping_mul(2u64.wrapping_sub(n64.wrapping_mul(x)));
    }
    (x & 0xFFFF_FFFF) as u32
}

fn encode_adb_public_key(key: &RsaPrivateKey) -> Result<Vec<u8>, WirelessError> {
    let public = key.to_public_key();
    let n = public.n();
    let e = public.e();

    let n_le = n.to_bytes_le();
    let n_low = u32::from_le_bytes(n_le[0..4].try_into().unwrap());
    let n0inv = 0u32.wrapping_sub(inv_mod_2_32(n_low));

    // rr = 2^4096 mod n
    let rr = (BigUint::from(1u8) << 4096usize) % n.clone();
    let rr_le = rr.to_bytes_le();
    let e_le = e.to_bytes_le();

    let mut buf = Vec::with_capacity(RSA_PUBLIC_KEY_SIZE);
    buf.extend_from_slice(&ADB_PUBKEY_MODULUS_SIZE_WORDS.to_le_bytes());
    buf.extend_from_slice(&n0inv.to_le_bytes());
    buf.extend_from_slice(&left_pad_to(&n_le, ADB_PUBKEY_MODULUS_SIZE));
    buf.extend_from_slice(&left_pad_to(&rr_le, ADB_PUBKEY_MODULUS_SIZE));
    buf.extend_from_slice(&left_pad_to(&e_le, 4));
    debug_assert_eq!(buf.len(), RSA_PUBLIC_KEY_SIZE);

    let mut out = BASE64.encode(&buf).into_bytes();
    out.push(b' ');
    out.extend_from_slice(PUBKEY_NAME);
    out.push(0);
    Ok(out)
}

fn self_signed_cert(pkcs8_der: &[u8]) -> Result<Vec<u8>, WirelessError> {
    let pkcs8 = PrivatePkcs8KeyDer::from(pkcs8_der.to_vec());
    let key_pair = rcgen::KeyPair::from_pkcs8_der_and_sign_algo(&pkcs8, &rcgen::PKCS_RSA_SHA256)
        .map_err(|e| WirelessError::Key(format!("载入 RSA 密钥失败: {e}")))?;
    let mut params = rcgen::CertificateParams::new(vec!["00".to_string()])
        .map_err(|e| WirelessError::Key(format!("证书参数失败: {e}")))?;
    params.not_before = rcgen::date_time_ymd(1970, 1, 1);
    params.not_after = rcgen::date_time_ymd(2048, 1, 1);
    params.distinguished_name = rcgen::DistinguishedName::new();
    params.distinguished_name.push(rcgen::DnType::CommonName, "00");
    params.serial_number = Some(rcgen::SerialNumber::from(vec![1u8]));
    let cert = params
        .self_signed(&key_pair)
        .map_err(|e| WirelessError::Key(format!("生成自签证书失败: {e}")))?;
    Ok(cert.der().to_vec())
}

impl AdbKey {
    /// 从应用私有目录载入；不存在则生成并写入。
    pub fn load_or_create(path: &Path) -> Result<Self, WirelessError> {
        let pkcs8_der = if path.exists() {
            fs::read(path)?
        } else {
            let mut rng = rsa::rand_core::OsRng;
            let key = RsaPrivateKey::new(&mut rng, 2048)
                .map_err(|e| WirelessError::Key(format!("生成 RSA 密钥失败: {e}")))?;
            let der = key
                .to_pkcs8_der()
                .map_err(|e| WirelessError::Key(format!("编码 PKCS#8 失败: {e}")))?;
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::write(path, der.as_bytes())?;
            der.as_bytes().to_vec()
        };
        let key = RsaPrivateKey::from_pkcs8_der(&pkcs8_der)
            .map_err(|e| WirelessError::Key(format!("解析 PKCS#8 失败: {e}")))?;
        let cert_der = self_signed_cert(&pkcs8_der)?;
        let pubkey_bytes = encode_adb_public_key(&key)?;
        Ok(Self { key, pkcs8_der, cert_der, pubkey_bytes })
    }

    pub fn adb_public_key(&self) -> &[u8] {
        &self.pubkey_bytes
    }

    /// TLS 1.3 客户端配置，携带与配对公钥同源的客户端证书。
    pub fn client_config(&self) -> Result<rustls::ClientConfig, WirelessError> {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let cfg = rustls::ClientConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(WirelessError::Tls)?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(TrustAllVerifier))
            .with_client_auth_cert(
                vec![CertificateDer::from(self.cert_der.clone())],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.pkcs8_der.clone())),
            )
            .map_err(WirelessError::Tls)?;
        Ok(cfg)
    }

    /// A_AUTH 阶段对 20 字节 token 做 PKCS#1 v1.5 私钥运算。
    pub fn sign_token(&self, token: &[u8]) -> Result<Vec<u8>, WirelessError> {
        let mut digest = Vec::with_capacity(DIGEST_INFO_SHA1.len() + token.len());
        digest.extend_from_slice(DIGEST_INFO_SHA1);
        digest.extend_from_slice(token);
        self.key
            .sign(Pkcs1v15Sign::new_unprefixed(), &digest)
            .map_err(|e| WirelessError::Key(format!("ADB 认证签名失败: {e}")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modular_inverse_is_correct() {
        for n in [1u32, 3, 5, 7, 65537, 0xdead_beef, 0xffff_ffff] {
            let inv = inv_mod_2_32(n);
            assert_eq!((n as u64) * (inv as u64) & 0xFFFF_FFFF, 1, "n={n}");
        }
    }

    #[test]
    fn adb_public_key_has_expected_layout() {
        let dir = std::env::temp_dir().join("broker-wireless-key-test");
        let path = dir.join("adb_key.pk8");
        let _ = fs::remove_file(&path);
        let key = AdbKey::load_or_create(&path).unwrap();
        let encoded = key.adb_public_key();
        // base64(524) = 700 字符，追加 " privbroker\0" = 12 字节
        assert_eq!(encoded.len(), 700 + 1 + PUBKEY_NAME.len() + 1);
        assert!(encoded.ends_with(b"privbroker\0"));
        let b64 = &encoded[..700];
        let raw = BASE64.decode(b64).unwrap();
        assert_eq!(raw.len(), RSA_PUBLIC_KEY_SIZE);
        assert_eq!(
            u32::from_le_bytes(raw[0..4].try_into().unwrap()),
            ADB_PUBKEY_MODULUS_SIZE_WORDS
        );
        assert_eq!(u32::from_le_bytes(raw[520..524].try_into().unwrap()), 65537);
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn key_is_persisted_and_reloaded() {
        let dir = std::env::temp_dir().join("broker-wireless-key-test");
        let path = dir.join("reload.pk8");
        let _ = fs::remove_file(&path);
        let a = AdbKey::load_or_create(&path).unwrap();
        let b = AdbKey::load_or_create(&path).unwrap();
        assert_eq!(a.adb_public_key(), b.adb_public_key());
        assert!(a.client_config().is_ok());
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn signature_is_256_bytes_and_reproducible() {
        let dir = std::env::temp_dir().join("broker-wireless-key-test");
        let path = dir.join("sign.pk8");
        let _ = fs::remove_file(&path);
        let key = AdbKey::load_or_create(&path).unwrap();
        let token = [0x5au8; 20];
        let sig = key.sign_token(&token).unwrap();
        assert_eq!(sig.len(), 256);
        assert_eq!(sig, key.sign_token(&token).unwrap());
        let _ = fs::remove_file(&path);
    }
}
