//! ADB 传输层：24 字节小端消息帧、`A_STLS` 升级、认证与 `shell:` 通道。
//!
//! 与配对层共用同一把 RSA 密钥：TLS 客户端证书即配对时注册的公钥，
//! 设备端据此授权连接。若服务端回退到 `A_AUTH`，则用同一密钥做签名认证。

use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

use crate::error::WirelessError;
use crate::key::AdbKey;
use crate::tls::TlsStream;

pub const A_SYNC: u32 = 0x434e_5953;
pub const A_CNXN: u32 = 0x4e58_4e43;
pub const A_AUTH: u32 = 0x4854_5541;
pub const A_OPEN: u32 = 0x4e45_504f;
pub const A_OKAY: u32 = 0x5941_4b4f;
pub const A_CLSE: u32 = 0x4553_4c43;
pub const A_WRTE: u32 = 0x4554_5257;
pub const A_STLS: u32 = 0x534c_5453;

pub const A_VERSION: u32 = 0x0100_0000;
pub const A_MAXDATA: u32 = 4096;
pub const A_STLS_VERSION: u32 = 0x0100_0000;

pub const ADB_AUTH_TOKEN: u32 = 1;
pub const ADB_AUTH_SIGNATURE: u32 = 2;
pub const ADB_AUTH_RSAPUBLICKEY: u32 = 3;

pub const HEADER_LENGTH: usize = 24;
/// 单帧数据上限，防止异常长度导致大分配。
pub const MAX_FRAME_BYTES: usize = 1024 * 1024;
const IO_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdbMessage {
    pub command: u32,
    pub arg0: u32,
    pub arg1: u32,
    pub data: Vec<u8>,
}

/// data checksum 为所有字节的无符号累加（非 CRC32 多项式）。
pub fn checksum(data: &[u8]) -> u32 {
    data.iter().fold(0u32, |acc, &b| acc.wrapping_add(b as u32))
}

impl AdbMessage {
    pub fn new(command: u32, arg0: u32, arg1: u32, data: Vec<u8>) -> Self {
        Self { command, arg0, arg1, data }
    }

    /// 字符串负载需要补结尾 NUL。
    pub fn new_str(command: u32, arg0: u32, arg1: u32, text: &str) -> Self {
        let mut data = text.as_bytes().to_vec();
        data.push(0);
        Self::new(command, arg0, arg1, data)
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_LENGTH + self.data.len());
        out.extend_from_slice(&self.command.to_le_bytes());
        out.extend_from_slice(&self.arg0.to_le_bytes());
        out.extend_from_slice(&self.arg1.to_le_bytes());
        out.extend_from_slice(&(self.data.len() as u32).to_le_bytes());
        out.extend_from_slice(&checksum(&self.data).to_le_bytes());
        out.extend_from_slice(&(self.command ^ 0xFFFF_FFFF).to_le_bytes());
        out.extend_from_slice(&self.data);
        out
    }

    pub fn parse_header(header: &[u8; HEADER_LENGTH]) -> Result<(u32, u32, u32, usize, u32, u32), WirelessError> {
        let u = |r: std::ops::Range<usize>| -> u32 {
            u32::from_le_bytes(header[r].try_into().unwrap())
        };
        let command = u(0..4);
        let arg0 = u(4..8);
        let arg1 = u(8..12);
        let data_length = u(12..16) as usize;
        let data_checksum = u(16..20);
        let magic = u(20..24);
        if magic != (command ^ 0xFFFF_FFFF) {
            return Err(WirelessError::Protocol("ADB 消息 magic 校验失败".into()));
        }
        if data_length > MAX_FRAME_BYTES {
            return Err(WirelessError::Protocol(format!(
                "ADB 消息长度越界 {data_length}"
            )));
        }
        Ok((command, arg0, arg1, data_length, data_checksum, magic))
    }
}

/// 明文 TCP 或已升级的 TLS 通道。
pub enum Transport {
    Plain(TcpStream),
    Tls(TlsStream),
}

impl Transport {
    pub fn read_exact(&mut self, buf: &mut [u8]) -> Result<(), WirelessError> {
        match self {
            Transport::Plain(t) => t.read_exact(buf).map_err(WirelessError::Io),
            Transport::Tls(t) => t.read_exact(buf),
        }
    }

    pub fn write_all(&mut self, data: &[u8]) -> Result<(), WirelessError> {
        match self {
            Transport::Plain(t) => {
                t.write_all(data)?;
                t.flush()?;
                Ok(())
            }
            Transport::Tls(t) => t.write_all(data),
        }
    }

    /// 调整底层 socket 读超时。后台启动命令投递后只需短暂等待回显，
    /// 不能沿用握手阶段的 15~20 秒，否则某些 ROM 不发 A_CLSE 会让激活卡很久。
    pub fn set_read_timeout(&mut self, dur: Option<Duration>) -> Result<(), WirelessError> {
        match self {
            Transport::Plain(t) => t.set_read_timeout(dur).map_err(WirelessError::Io),
            Transport::Tls(t) => t.set_read_timeout(dur).map_err(WirelessError::Io),
        }
    }
}

pub fn read_message(transport: &mut Transport) -> Result<AdbMessage, WirelessError> {
    let mut header = [0u8; HEADER_LENGTH];
    transport.read_exact(&mut header)?;
    let (command, arg0, arg1, len, expected_crc, _magic) = AdbMessage::parse_header(&header)?;
    let mut data = vec![0u8; len];
    if len > 0 {
        transport.read_exact(&mut data)?;
        if checksum(&data) != expected_crc {
            return Err(WirelessError::Protocol("ADB 消息 checksum 校验失败".into()));
        }
    }
    Ok(AdbMessage { command, arg0, arg1, data })
}

pub fn write_message(transport: &mut Transport, msg: &AdbMessage) -> Result<(), WirelessError> {
    transport.write_all(&msg.encode())
}

pub struct AdbClient {
    transport: Transport,
}

impl AdbClient {
    /// 连接设备端 ADB TLS 服务并完成握手/认证。
    pub fn connect(host: &str, port: u16, key: &AdbKey) -> Result<Self, WirelessError> {
        // 与配对层共用带超时的连接，便于在多个候选地址间快速切换。
        let tcp = crate::tls::connect_tcp(host, port)?;
        tcp.set_nodelay(true)?;
        tcp.set_read_timeout(Some(IO_TIMEOUT))?;
        tcp.set_write_timeout(Some(IO_TIMEOUT))?;
        let mut transport = Transport::Plain(tcp);

        write_message(
            &mut transport,
            &AdbMessage::new_str(A_CNXN, A_VERSION, A_MAXDATA, "host::"),
        )?;
        let mut message = read_message(&mut transport)?;

        if message.command == A_STLS {
            write_message(
                &mut transport,
                &AdbMessage::new(A_STLS, A_STLS_VERSION, 0, Vec::new()),
            )?;
            let tcp = match transport {
                Transport::Plain(t) => t,
                Transport::Tls(_) => unreachable!(),
            };
            let tls = TlsStream::wrap(tcp, key.client_config()?, host)?;
            transport = Transport::Tls(tls);
            message = read_message(&mut transport)?;
        } else if message.command == A_AUTH {
            let signature = key.sign_token(&message.data)?;
            write_message(
                &mut transport,
                &AdbMessage::new(A_AUTH, ADB_AUTH_SIGNATURE, 0, signature),
            )?;
            message = read_message(&mut transport)?;
            if message.command != A_CNXN {
                write_message(
                    &mut transport,
                    &AdbMessage::new(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adb_public_key().to_vec()),
                )?;
                message = read_message(&mut transport)?;
            }
        }

        if message.command != A_CNXN {
            return Err(WirelessError::Protocol(format!(
                "ADB 握手未收到 A_CNXN，实际 command=0x{:08x}",
                message.command
            )));
        }
        Ok(Self { transport })
    }

    /// 打开 `shell:` 通道执行一条命令并收集输出。
    ///
    /// 启动特权进程的命令需自行以 `&` 后台化，避免通道长时间挂起。
    pub fn shell(&mut self, command: &str) -> Result<Vec<u8>, WirelessError> {
        const LOCAL_ID: u32 = 1;
        write_message(
            &mut self.transport,
            &AdbMessage::new_str(A_OPEN, LOCAL_ID, 0, &format!("shell:{command}")),
        )?;

        let mut output = Vec::new();
        loop {
            let message = read_message(&mut self.transport)?;
            match message.command {
                A_OKAY => continue,
                A_WRTE => {
                    // A_WRTE.arg0 是对端 local id，回包 A_OKAY.arg1 指向它。
                    output.extend_from_slice(&message.data);
                    write_message(
                        &mut self.transport,
                        &AdbMessage::new(A_OKAY, LOCAL_ID, message.arg0, Vec::new()),
                    )?;
                }
                A_CLSE => {
                    write_message(
                        &mut self.transport,
                        &AdbMessage::new(A_CLSE, LOCAL_ID, message.arg0, Vec::new()),
                    )?;
                    break;
                }
                other => {
                    return Err(WirelessError::Protocol(format!(
                        "shell 通道收到非预期命令 0x{other:08x}"
                    )));
                }
            }
        }
        Ok(output)
    }

    /// 打开 `shell:` 通道投递一条后台启动命令，并尽量收集回显。
    ///
    /// 与 `shell` 不同：不把「收到 `A_CLSE`」当作成功的必要条件。命令一旦写入
    /// 通道就已投递，之后无论收到 `A_CLSE`、读超时还是 EOF，都返回已收集的输出。
    /// 特权代理是常驻进程，部分 ROM 的 adbd 会迟迟不关闭后台命令的 shell 通道，
    /// 死等 `A_CLSE` 只会卡到读超时并误报失败。真正是否常驻由界面探测 abstract
    /// socket 判定，这里只负责投递与把启动日志带回去用于诊断。
    pub fn shell_detached(&mut self, command: &str) -> Result<Vec<u8>, WirelessError> {
        const LOCAL_ID: u32 = 1;
        const DETACHED_READ_TIMEOUT: Duration = Duration::from_secs(6);
        write_message(
            &mut self.transport,
            &AdbMessage::new_str(A_OPEN, LOCAL_ID, 0, &format!("shell:{command}")),
        )?;
        let _ = self.transport.set_read_timeout(Some(DETACHED_READ_TIMEOUT));
        let mut output = Vec::new();
        loop {
            match read_message(&mut self.transport) {
                Ok(message) => match message.command {
                    A_OKAY => continue,
                    A_WRTE => {
                        output.extend_from_slice(&message.data);
                        let _ = write_message(
                            &mut self.transport,
                            &AdbMessage::new(A_OKAY, LOCAL_ID, message.arg0, Vec::new()),
                        );
                    }
                    A_CLSE => {
                        let _ = write_message(
                            &mut self.transport,
                            &AdbMessage::new(A_CLSE, LOCAL_ID, message.arg0, Vec::new()),
                        );
                        break;
                    }
                    _ => continue,
                },
                // 读超时 / EOF / 连接被重置都发生在命令投递之后，视为已投递。
                Err(_) => break,
            }
        }
        Ok(output)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn message_roundtrip_and_string_terminator() {
        let msg = AdbMessage::new_str(A_OPEN, 1, 0, "shell:echo ok");
        let bytes = msg.encode();
        assert_eq!(bytes.len(), HEADER_LENGTH + msg.data.len());
        assert_eq!(msg.data.last(), Some(&0));
        assert_eq!(&bytes[0..4], &A_OPEN.to_le_bytes());
        assert_eq!(&bytes[12..16], &(msg.data.len() as u32).to_le_bytes());
        assert_eq!(&bytes[16..20], &checksum(&msg.data).to_le_bytes());
        assert_eq!(&bytes[20..24], &(A_OPEN ^ 0xFFFF_FFFF).to_le_bytes());
        let mut header = [0u8; HEADER_LENGTH];
        header.copy_from_slice(&bytes[..HEADER_LENGTH]);
        let (cmd, a0, a1, len, crc, magic) = AdbMessage::parse_header(&header).unwrap();
        assert_eq!((cmd, a0, a1, len), (A_OPEN, 1, 0, msg.data.len()));
        assert_eq!(crc, checksum(&msg.data));
        assert_eq!(magic, A_OPEN ^ 0xFFFF_FFFF);
    }

    #[test]
    fn checksum_is_plain_byte_sum() {
        assert_eq!(checksum(&[1, 2, 3, 255]), 261);
        assert_eq!(checksum(&[]), 0);
    }

    #[test]
    fn header_rejects_bad_magic_and_oversize() {
        let mut h = [0u8; HEADER_LENGTH];
        h[0..4].copy_from_slice(&A_CNXN.to_le_bytes());
        h[20..24].copy_from_slice(&0u32.to_le_bytes());
        assert!(AdbMessage::parse_header(&h).is_err());

        let mut h = [0u8; HEADER_LENGTH];
        h[0..4].copy_from_slice(&A_CNXN.to_le_bytes());
        h[20..24].copy_from_slice(&(A_CNXN ^ 0xFFFF_FFFF).to_le_bytes());
        h[12..16].copy_from_slice(&((MAX_FRAME_BYTES as u32) + 1).to_le_bytes());
        assert!(AdbMessage::parse_header(&h).is_err());
    }

    // 明文路径的端到端测试：模拟设备端完成握手与 shell 通道。
    #[test]
    fn shell_stream_over_plain_transport() {
        use std::net::TcpListener;
        use std::thread;

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = thread::spawn(move || {
            let (mut tcp, _) = listener.accept().unwrap();
            let mut t = Transport::Plain(tcp.try_clone().unwrap());
            let cnxn = read_message(&mut t).unwrap();
            assert_eq!(cnxn.command, A_CNXN);
            assert_eq!(&cnxn.data, b"host::\0");
            write_message(&mut t, &AdbMessage::new_str(A_CNXN, A_VERSION, A_MAXDATA, "device::")).unwrap();

            let open = read_message(&mut t).unwrap();
            assert_eq!(open.command, A_OPEN);
            assert!(String::from_utf8_lossy(&open.data).starts_with("shell:"));
            write_message(&mut t, &AdbMessage::new(A_OKAY, 1, 7, Vec::new())).unwrap();
            write_message(&mut t, &AdbMessage::new(A_WRTE, 7, 1, b"broker started\n".to_vec())).unwrap();
            let ack = read_message(&mut t).unwrap();
            assert_eq!(ack.command, A_OKAY);
            assert_eq!((ack.arg0, ack.arg1), (1, 7));
            write_message(&mut t, &AdbMessage::new(A_CLSE, 7, 1, Vec::new())).unwrap();
            let _clse = read_message(&mut t).unwrap();
            // 让 socket 保持到客户端读完
            tcp.flush().ok();
        });

        let dir = std::env::temp_dir().join("broker-wireless-adb-test");
        let path = dir.join("adb_key.pk8");
        let _ = std::fs::remove_file(&path);
        let key = AdbKey::load_or_create(&path).unwrap();
        let mut client = AdbClient::connect("127.0.0.1", port, &key).unwrap();
        let out = client.shell("echo broker").unwrap();
        let _ = std::fs::remove_file(&path);
        handle.join().unwrap();
        assert_eq!(out, b"broker started\n");
    }

    /// 无线调试的实际路径：明文 A_CNXN → A_STLS → 同一 socket 上升 TLS → A_CNXN → shell。
    #[test]
    fn stls_upgrade_and_shell_over_tls() {
        use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
        use std::io::{self, Read, Write};
        use std::net::TcpListener;
        use std::sync::Arc;

        fn server_cfg() -> rustls::ServerConfig {
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

        struct TlsSrv {
            tcp: TcpStream,
            conn: rustls::ServerConnection,
        }
        impl TlsSrv {
            fn handshake(tcp: TcpStream, cfg: Arc<rustls::ServerConfig>) -> io::Result<Self> {
                let conn = rustls::ServerConnection::new(cfg).map_err(io::Error::other)?;
                let mut s = Self { tcp, conn };
                while s.conn.is_handshaking() {
                    s.flush()?;
                    let n = s.conn.read_tls(&mut s.tcp)?;
                    if n == 0 {
                        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "TLS EOF"));
                    }
                    s.conn.process_new_packets().map_err(io::Error::other)?;
                }
                s.flush()?;
                Ok(s)
            }
            fn flush(&mut self) -> io::Result<()> {
                while self.conn.wants_write() {
                    if self.conn.write_tls(&mut self.tcp)? == 0 {
                        break;
                    }
                }
                self.tcp.flush()
            }
            fn read_exact(&mut self, buf: &mut [u8]) -> io::Result<()> {
                let mut off = 0;
                while off < buf.len() {
                    match self.conn.reader().read(&mut buf[off..]) {
                        Ok(n) => {
                            if n > 0 {
                                off += n;
                                continue;
                            }
                            return Ok(());
                        }
                        Err(e) if e.kind() == io::ErrorKind::WouldBlock => {}
                        Err(e) => return Err(e),
                    }
                    let m = self.conn.read_tls(&mut self.tcp)?;
                    if m == 0 {
                        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "TLS EOF"));
                    }
                    self.conn.process_new_packets().map_err(io::Error::other)?;
                }
                Ok(())
            }
            fn write_all(&mut self, data: &[u8]) -> io::Result<()> {
                {
                    let mut w = self.conn.writer();
                    w.write_all(data)?;
                    w.flush()?;
                }
                self.flush()
            }
        }
        fn srv_read(s: &mut TlsSrv) -> AdbMessage {
            let mut h = [0u8; HEADER_LENGTH];
            s.read_exact(&mut h).unwrap();
            let (cmd, a0, a1, len, crc, _) = AdbMessage::parse_header(&h).unwrap();
            let mut data = vec![0u8; len];
            if len > 0 {
                s.read_exact(&mut data).unwrap();
                assert_eq!(checksum(&data), crc);
            }
            AdbMessage { command: cmd, arg0: a0, arg1: a1, data }
        }
        fn srv_write(s: &mut TlsSrv, m: &AdbMessage) {
            s.write_all(&m.encode()).unwrap();
        }

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = std::thread::spawn(move || {
            let (tcp, _) = listener.accept().unwrap();
            tcp.set_read_timeout(Some(Duration::from_secs(15))).unwrap();
            let mut plain = Transport::Plain(tcp.try_clone().unwrap());
            assert_eq!(read_message(&mut plain).unwrap().command, A_CNXN);
            write_message(&mut plain, &AdbMessage::new(A_STLS, A_STLS_VERSION, 0, Vec::new())).unwrap();
            assert_eq!(read_message(&mut plain).unwrap().command, A_STLS);
            drop(plain);

            let mut tls = TlsSrv::handshake(tcp, Arc::new(server_cfg())).unwrap();
            srv_write(&mut tls, &AdbMessage::new_str(A_CNXN, A_VERSION, A_MAXDATA, "device::"));
            let open = srv_read(&mut tls);
            assert_eq!(open.command, A_OPEN);
            srv_write(&mut tls, &AdbMessage::new(A_OKAY, 1, 7, Vec::new()));
            srv_write(&mut tls, &AdbMessage::new(A_WRTE, 7, 1, b"privilege broker ready\n".to_vec()));
            assert_eq!(srv_read(&mut tls).command, A_OKAY);
            srv_write(&mut tls, &AdbMessage::new(A_CLSE, 7, 1, Vec::new()));
            let _ = srv_read(&mut tls);
        });

        let dir = std::env::temp_dir().join("broker-wireless-adb-test");
        let path = dir.join("stls.pk8");
        let _ = std::fs::remove_file(&path);
        let key = AdbKey::load_or_create(&path).unwrap();
        let mut client = AdbClient::connect("127.0.0.1", port, &key).unwrap();
        let out = client.shell("start broker").unwrap();
        let _ = std::fs::remove_file(&path);
        handle.join().unwrap();
        assert_eq!(out, b"privilege broker ready\n");
    }

    /// 后台启动命令的关键容错：设备端只回显日志、迟迟不发 A_CLSE（甚至直接
    /// 关闭连接），shell_detached 也必须把已收集到的启动日志带回来，不能报错。
    /// 否则界面只会看到「激活失败」，无从判断代理是否其实已经起来了。
    #[test]
    fn shell_detached_returns_output_without_aclse() {
        use std::net::{TcpListener, TcpStream};
        use std::thread;

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = thread::spawn(move || {
            let (tcp, _) = listener.accept().unwrap();
            let mut t = Transport::Plain(tcp);
            let open = read_message(&mut t).unwrap();
            assert_eq!(open.command, A_OPEN);
            assert!(String::from_utf8_lossy(&open.data).starts_with("shell:"));
            write_message(&mut t, &AdbMessage::new(A_OKAY, 1, 7, Vec::new())).unwrap();
            write_message(&mut t, &AdbMessage::new(
                A_WRTE, 7, 1, b"listening: abstract:prvbrk.abc\n".to_vec())).unwrap();
            // 故意不发 A_CLSE，离开作用域即关闭 socket。
        });

        let tcp = TcpStream::connect(("127.0.0.1", port)).unwrap();
        tcp.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let mut client = AdbClient { transport: Transport::Plain(tcp) };
        let out = client.shell_detached("start broker").unwrap();
        handle.join().unwrap();
        assert_eq!(out, b"listening: abstract:prvbrk.abc\n");
    }
}
