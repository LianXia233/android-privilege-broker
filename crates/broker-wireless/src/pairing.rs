//! 无线调试配对客户端：TLS 1.3 握手 → exporter → SPAKE2 → AES-GCM 帧 → 注册公钥。
//!
//! 帧格式（大端）：
//! * `PairingPacketHeader` 6 字节：version(1) / type(1) / payload_len(4)
//! * type 0 = SPAKE2_MSG（明文 32 字节），type 1 = PEER_INFO（密文）
//! * `PeerInfo` 固定 8192 字节：type(1) + data(8191)，type 0 为 ADB RSA 公钥

use crate::crypto::GcmCipher;
use crate::error::WirelessError;
use crate::key::{AdbKey, PEER_INFO_SIZE};
use crate::spake2::{self, Role};
use crate::tls::{random_64, TlsStream};

pub const KEY_HEADER_VERSION: u8 = 1;
pub const TYPE_SPAKE2_MSG: u8 = 0;
pub const TYPE_PEER_INFO: u8 = 1;
pub const HEADER_SIZE: usize = 6;
pub const MAX_PEER_INFO_SIZE: usize = PEER_INFO_SIZE;
pub const MAX_PAYLOAD_SIZE: usize = MAX_PEER_INFO_SIZE * 2;
pub const PEER_INFO_TYPE_ADB_RSA_PUB_KEY: u8 = 0;
pub const EXPORTER_LABEL: &[u8] = b"adb-label\0";
pub const EXPORTER_SIZE: usize = 64;

pub struct PairingOutcome {
    pub peer_info_type: u8,
}

/// 解析 6 字节包头。
pub fn decode_header(bytes: &[u8; HEADER_SIZE]) -> Result<(u8, usize), WirelessError> {
    let version = bytes[0];
    let typ = bytes[1];
    let payload = u32::from_be_bytes([bytes[2], bytes[3], bytes[4], bytes[5]]) as usize;
    if version != KEY_HEADER_VERSION {
        return Err(WirelessError::Protocol(format!("不支持的配对包头版本 {version}")));
    }
    if typ != TYPE_SPAKE2_MSG && typ != TYPE_PEER_INFO {
        return Err(WirelessError::Protocol(format!("未知配对包类型 {typ}")));
    }
    if payload == 0 || payload > MAX_PAYLOAD_SIZE {
        return Err(WirelessError::Protocol(format!("配对包长度越界 {payload}")));
    }
    Ok((typ, payload))
}

pub fn encode_header(typ: u8, payload_len: usize) -> [u8; HEADER_SIZE] {
    let mut h = [0u8; HEADER_SIZE];
    h[0] = KEY_HEADER_VERSION;
    h[1] = typ;
    h[2..].copy_from_slice(&(payload_len as u32).to_be_bytes());
    h
}

pub fn write_packet(
    stream: &mut TlsStream,
    typ: u8,
    payload: &[u8],
) -> Result<(), WirelessError> {
    let mut frame = Vec::with_capacity(HEADER_SIZE + payload.len());
    frame.extend_from_slice(&encode_header(typ, payload.len()));
    frame.extend_from_slice(payload);
    stream.write_all(&frame)
}

pub fn read_packet(stream: &mut TlsStream) -> Result<(u8, Vec<u8>), WirelessError> {
    let mut header = [0u8; HEADER_SIZE];
    stream.read_exact(&mut header)?;
    let (typ, len) = decode_header(&header)?;
    let mut payload = vec![0u8; len];
    stream.read_exact(&mut payload)?;
    Ok((typ, payload))
}

/// 组装固定 8192 字节 PeerInfo。
pub fn build_peer_info(public_key: &[u8]) -> Result<Vec<u8>, WirelessError> {
    if public_key.len() > PEER_INFO_SIZE - 1 {
        return Err(WirelessError::Protocol("ADB 公钥超过 PeerInfo 容量".into()));
    }
    let mut peer = vec![0u8; PEER_INFO_SIZE];
    peer[0] = PEER_INFO_TYPE_ADB_RSA_PUB_KEY;
    peer[1..1 + public_key.len()].copy_from_slice(public_key);
    Ok(peer)
}

/// 校验 6 位配对码。
pub fn validate_pairing_code(code: &str) -> Result<(), WirelessError> {
    if code.len() != 6 || !code.bytes().all(|b| b.is_ascii_digit()) {
        return Err(WirelessError::Protocol("配对码必须是 6 位数字".into()));
    }
    Ok(())
}

/// 完成一次配对；成功后设备端已登记本客户端的 ADB RSA 公钥。
pub fn pair(
    host: &str,
    port: u16,
    pairing_code: &str,
    key: &AdbKey,
) -> Result<PairingOutcome, WirelessError> {
    validate_pairing_code(pairing_code)?;
    let mut stream = TlsStream::connect(host, port, key.client_config()?)?;

    let mut exported = [0u8; EXPORTER_SIZE];
    stream.export_keying_material(EXPORTER_LABEL, None, &mut exported)?;

    // password = 配对码字节 || 64 字节 TLS exporter 输出
    let mut password = Vec::with_capacity(pairing_code.len() + EXPORTER_SIZE);
    password.extend_from_slice(pairing_code.as_bytes());
    password.extend_from_slice(&exported);

    let random = random_64()?;
    let generated = spake2::generate(&password, Role::Alice, &random)?;

    write_packet(&mut stream, TYPE_SPAKE2_MSG, &generated.msg)?;
    let (typ, their_msg) = read_packet(&mut stream)?;
    if typ != TYPE_SPAKE2_MSG {
        return Err(WirelessError::Protocol("对端未返回 SPAKE2_MSG".into()));
    }
    if their_msg.len() != 32 {
        return Err(WirelessError::Protocol(format!(
            "SPAKE2 消息长度异常 {}",
            their_msg.len()
        )));
    }
    let mut peer_msg = [0u8; 32];
    peer_msg.copy_from_slice(&their_msg);
    let key_material = spake2::process(&generated.ctx, &peer_msg)?;
    let mut cipher = GcmCipher::new(&key_material)?;

    let peer = build_peer_info(key.adb_public_key())?;
    let encrypted = cipher.seal(&peer)?;
    write_packet(&mut stream, TYPE_PEER_INFO, &encrypted)?;

    let (typ2, their_encrypted) = read_packet(&mut stream)?;
    if typ2 != TYPE_PEER_INFO {
        return Err(WirelessError::Protocol("对端未返回 PEER_INFO".into()));
    }
    let decrypted = cipher.open(&their_encrypted)?;
    if decrypted.len() != PEER_INFO_SIZE {
        return Err(WirelessError::Protocol(format!(
            "对端 PeerInfo 长度异常 {}",
            decrypted.len()
        )));
    }
    Ok(PairingOutcome { peer_info_type: decrypted[0] })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::sync::mpsc;
    use std::sync::Arc;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};

    #[test]
    fn header_roundtrip_and_rejects_bad_fields() {
        let h = encode_header(TYPE_PEER_INFO, 8208);
        assert_eq!(h[0], 1);
        assert_eq!(h[1], 1);
        assert_eq!(decode_header(&h).unwrap(), (1, 8208));
        let mut bad = h;
        bad[0] = 2;
        assert!(decode_header(&bad).is_err());
        let mut bad = h;
        bad[1] = 9;
        assert!(decode_header(&bad).is_err());
        let mut bad = h;
        bad[2..].copy_from_slice(&0u32.to_be_bytes());
        assert!(decode_header(&bad).is_err());
        let mut bad = h;
        bad[2..].copy_from_slice(&(MAX_PAYLOAD_SIZE as u32 + 1).to_be_bytes());
        assert!(decode_header(&bad).is_err());
    }

    #[test]
    fn peer_info_layout_is_fixed_size() {
        let peer = build_peer_info(b"pubkey").unwrap();
        assert_eq!(peer.len(), PEER_INFO_SIZE);
        assert_eq!(peer[0], 0);
        assert_eq!(&peer[1..7], b"pubkey");
        assert_eq!(peer[7..].iter().sum::<u8>(), 0);
    }

    #[test]
    fn pairing_code_validation() {
        assert!(validate_pairing_code("123456").is_ok());
        assert!(validate_pairing_code("12345").is_err());
        assert!(validate_pairing_code("12345a").is_err());
        assert!(validate_pairing_code("").is_err());
    }

    // ---- 本地 TLS 集成测试：模拟设备端配对服务（Bob 角色）验证完整链路 ----

    fn server_tls_config() -> rustls::ServerConfig {
        let certified =
            rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).unwrap();
        let cert_der = certified.cert.der().to_vec();
        let key_der = certified.signing_key.serialize_der();
        rustls::ServerConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(cert_der)],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_der)),
        )
        .unwrap()
    }

    struct ServerTls {
        tcp: TcpStream,
        conn: rustls::ServerConnection,
    }

    impl ServerTls {
        fn accept(tcp: TcpStream, cfg: Arc<rustls::ServerConfig>) -> Result<Self, String> {
            tcp.set_read_timeout(Some(std::time::Duration::from_secs(15)))
                .map_err(|e| e.to_string())?;
            let conn = rustls::ServerConnection::new(cfg).map_err(|e| e.to_string())?;
            let mut s = Self { tcp, conn };
            while s.conn.is_handshaking() {
                s.flush().map_err(|e| e.to_string())?;
                let n = s.conn.read_tls(&mut s.tcp).map_err(|e| e.to_string())?;
                if n == 0 {
                    return Err("握手期间连接关闭".into());
                }
                s.conn.process_new_packets().map_err(|e| e.to_string())?;
            }
            s.flush().map_err(|e| e.to_string())?;
            Ok(s)
        }

        fn flush(&mut self) -> std::io::Result<()> {
            while self.conn.wants_write() {
                if self.conn.write_tls(&mut self.tcp)? == 0 {
                    break;
                }
            }
            self.tcp.flush()
        }

        fn read_exact(&mut self, buf: &mut [u8]) -> Result<(), String> {
            let mut off = 0;
            while off < buf.len() {
                match self.conn.reader().read(&mut buf[off..]) {
                    Ok(n) => {
                        if n > 0 {
                            off += n;
                            continue;
                        }
                        return Err("对端关闭 TLS 会话".into());
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                    Err(e) => return Err(format!("读取明文失败: {e}")),
                }
                let m = self
                    .conn
                    .read_tls(&mut self.tcp)
                    .map_err(|e| format!("读取 TLS 记录失败: {e}"))?;
                if m == 0 {
                    return Err("TLS 连接提前结束".into());
                }
                self.conn.process_new_packets().map_err(|e| e.to_string())?;
            }
            Ok(())
        }

        fn write_all(&mut self, data: &[u8]) -> Result<(), String> {
            {
                let mut writer = self.conn.writer();
                writer.write_all(data).map_err(|e| e.to_string())?;
                writer.flush().map_err(|e| e.to_string())?;
            }
            self.flush().map_err(|e| e.to_string())
        }

        fn export(&self, out: &mut [u8]) -> Result<(), String> {
            self.conn
                .export_keying_material(out, EXPORTER_LABEL, None)
                .map(|_| ())
                .map_err(|e| e.to_string())
        }
    }

    fn server_read_packet(s: &mut ServerTls) -> Result<(u8, Vec<u8>), String> {
        let mut h = [0u8; HEADER_SIZE];
        s.read_exact(&mut h)?;
        let (typ, len) = decode_header(&h).map_err(|e| e.to_string())?;
        let mut p = vec![0u8; len];
        s.read_exact(&mut p)?;
        Ok((typ, p))
    }

    fn server_write_packet(s: &mut ServerTls, typ: u8, payload: &[u8]) -> Result<(), String> {
        let mut frame = Vec::new();
        frame.extend_from_slice(&encode_header(typ, payload.len()));
        frame.extend_from_slice(payload);
        s.write_all(&frame)
    }

    fn server_task(listener: TcpListener, code: &str) -> Result<Vec<u8>, String> {
        let (tcp, _) = listener.accept().map_err(|e| e.to_string())?;
        let mut s = ServerTls::accept(tcp, Arc::new(server_tls_config()))?;
        let mut exported = [0u8; EXPORTER_SIZE];
        s.export(&mut exported)?;
        let mut password = code.as_bytes().to_vec();
        password.extend_from_slice(&exported);

        let (typ, alice_msg) = server_read_packet(&mut s)?;
        if typ != TYPE_SPAKE2_MSG || alice_msg.len() != 32 {
            return Err("客户端 SPAKE2 消息异常".into());
        }
        let random = [9u8; 64];
        let generated = spake2::generate(&password, Role::Bob, &random)
            .map_err(|e| e.to_string())?;
        server_write_packet(&mut s, TYPE_SPAKE2_MSG, &generated.msg)?;
        let mut msg = [0u8; 32];
        msg.copy_from_slice(&alice_msg);
        let km = spake2::process(&generated.ctx, &msg).map_err(|e| e.to_string())?;
        let mut cipher = GcmCipher::new(&km).map_err(|e| e.to_string())?;

        let (typ2, encrypted) = server_read_packet(&mut s)?;
        if typ2 != TYPE_PEER_INFO {
            return Err("客户端未发送 PEER_INFO".into());
        }
        let peer = cipher
            .open(&encrypted)
            .map_err(|e| format!("PEER_INFO_DECRYPT_FAILED: {e}"))?;
        if peer.len() != PEER_INFO_SIZE {
            return Err("PeerInfo 长度异常".into());
        }
        let my_peer = build_peer_info(b"device-side").map_err(|e| e.to_string())?;
        let reply = cipher.seal(&my_peer).map_err(|e| e.to_string())?;
        server_write_packet(&mut s, TYPE_PEER_INFO, &reply)?;
        Ok(peer[1..].to_vec())
    }

    #[test]
    fn full_pairing_roundtrip_over_local_tls() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (tx, rx) = mpsc::channel();
        let handle = std::thread::spawn(move || {
            let _ = tx.send(server_task(listener, "482913"));
        });

        let dir = std::env::temp_dir().join("broker-wireless-pair-test");
        let path = dir.join("adb_key.pk8");
        let _ = std::fs::remove_file(&path);
        let key = AdbKey::load_or_create(&path).unwrap();
        let expected_pub = key.adb_public_key().to_vec();

        let outcome = pair("127.0.0.1", port, "482913", &key);
        let server_result = rx.recv().unwrap();
        handle.join().unwrap();
        let _ = std::fs::remove_file(&path);

        assert!(
            outcome.is_ok(),
            "客户端配对失败: {:?}; 服务端结果: {:?}",
            outcome.as_ref().err(),
            server_result.as_ref().err()
        );
        assert_eq!(outcome.unwrap().peer_info_type, 0);
        let registered = server_result.expect("服务端配对失败");
        // 设备端解密出的 PeerInfo 前段必须逐字节等于本客户端生成的 ADB 公钥，
        // 余下为固定补零；公钥自身以 NUL 结尾，不能用「去尾零」方式裁剪。
        assert!(registered.starts_with(&expected_pub));
        assert_eq!(registered[expected_pub.len()..].iter().sum::<u8>(), 0);
    }

    #[test]
    fn wrong_pairing_code_fails_at_gcm_open() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (tx, rx) = mpsc::channel();
        let handle = std::thread::spawn(move || {
            let _ = tx.send(server_task(listener, "111111"));
        });

        let dir = std::env::temp_dir().join("broker-wireless-pair-test");
        let path = dir.join("wrong_code.pk8");
        let _ = std::fs::remove_file(&path);
        let key = AdbKey::load_or_create(&path).unwrap();
        let outcome = pair("127.0.0.1", port, "222222", &key);
        let server_result = rx.recv().unwrap();
        handle.join().unwrap();
        let _ = std::fs::remove_file(&path);

        // 链路必须走到 PEER_INFO 解密阶段才失败（排除 TLS/SPAKE2 假阳性）：
        // 配对码不一致时设备端无法解密客户端 PeerInfo，随后断开连接。
        assert!(outcome.is_err());
        let srv_err = server_result.expect_err("错误配对码不应让服务端成功");
        assert!(
            srv_err.contains("PEER_INFO_DECRYPT_FAILED"),
            "服务端未在预期阶段失败: {srv_err}"
        );
    }
}
