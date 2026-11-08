//! 同步 TLS 1.3 通道封装。
//!
//! rustls 不持有 socket，本模块负责驱动 `read_tls` / `write_tls` /
//! `process_new_packets`，向上提供明文 `read_exact` / `write_all` 与
//! `export_keying_material`。配对与 ADB TLS 升级共用同一实现。

use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::Arc;
use std::time::Duration;

use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection};

use crate::error::WirelessError;

const IO_TIMEOUT: Duration = Duration::from_secs(15);

pub struct TlsStream {
    tcp: TcpStream,
    conn: ClientConnection,
}

fn server_name(host: &str) -> Result<ServerName<'static>, WirelessError> {
    ServerName::try_from(host.to_string())
        .map_err(|e| WirelessError::Protocol(format!("非法服务名 {host}: {e}")))
}

/// 带超时的 TCP 连接。无线调试的候选地址可能有多个（WLAN 接口 IP、本机
/// 其它网卡、loopback），某个不可达的地址若用系统默认 connect 超时会阻塞
/// 数十秒，逐个尝试时必须给出明确上限。
pub fn connect_tcp(host: &str, port: u16) -> Result<TcpStream, WirelessError> {
    use std::net::ToSocketAddrs;
    let addrs: Vec<_> = (host, port).to_socket_addrs()?.collect();
    let mut last: Option<std::io::Error> = None;
    for addr in addrs {
        match TcpStream::connect_timeout(&addr, Duration::from_secs(3)) {
            Ok(stream) => return Ok(stream),
            Err(e) => last = Some(e),
        }
    }
    Err(WirelessError::Io(last.unwrap_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::AddrNotAvailable,
            format!("{host}:{port} 无法解析为可连接地址"))
    })))
}

impl TlsStream {
    /// 新建 TCP 连接并完成 TLS 握手。
    pub fn connect(host: &str, port: u16, cfg: ClientConfig) -> Result<Self, WirelessError> {
        let tcp = connect_tcp(host, port)?;
        Self::wrap(tcp, cfg, host)
    }

    /// 在已有 TCP 连接上完成 TLS 握手（ADB `A_STLS` 升级场景）。
    pub fn wrap(tcp: TcpStream, cfg: ClientConfig, host: &str) -> Result<Self, WirelessError> {
        tcp.set_nodelay(true)?;
        tcp.set_read_timeout(Some(IO_TIMEOUT))?;
        tcp.set_write_timeout(Some(IO_TIMEOUT))?;
        let name = server_name(host)?;
        let conn = ClientConnection::new(Arc::new(cfg), name)
            .map_err(WirelessError::Tls)?;
        let mut stream = Self { tcp, conn };
        stream.handshake()?;
        Ok(stream)
    }

    fn handshake(&mut self) -> Result<(), WirelessError> {
        while self.conn.is_handshaking() {
            self.flush_tls()?;
            let n = self.conn.read_tls(&mut self.tcp)?;
            if n == 0 {
                return Err(WirelessError::Io(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "TLS 握手期间连接被关闭",
                )));
            }
            self.conn.process_new_packets().map_err(WirelessError::Tls)?;
        }
        self.flush_tls()?;
        Ok(())
    }

    fn flush_tls(&mut self) -> Result<(), WirelessError> {
        while self.conn.wants_write() {
            let n = self.conn.write_tls(&mut self.tcp)?;
            if n == 0 {
                break;
            }
        }
        self.tcp.flush()?;
        Ok(())
    }

    /// 从 TLS 通道读取任意长度明文；返回 0 表示对端关闭。
    pub fn read_plaintext(&mut self, buf: &mut [u8]) -> Result<usize, WirelessError> {
        loop {
            // rustls 0.23 在无明文缓冲时返回 WouldBlock，代表需要继续喂入 TLS 记录。
            match self.conn.reader().read(buf) {
                Ok(n) => {
                    if n > 0 {
                        return Ok(n);
                    }
                    return Ok(0);
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => {}
                Err(e) => return Err(WirelessError::Io(e)),
            }
            let m = self.conn.read_tls(&mut self.tcp)?;
            if m == 0 {
                return Ok(0);
            }
            self.conn.process_new_packets().map_err(WirelessError::Tls)?;
        }
    }

    pub fn read_exact(&mut self, buf: &mut [u8]) -> Result<(), WirelessError> {
        let mut off = 0;
        while off < buf.len() {
            let n = self.read_plaintext(&mut buf[off..])?;
            if n == 0 {
                return Err(WirelessError::Io(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "TLS 明文流提前结束",
                )));
            }
            off += n;
        }
        Ok(())
    }

    pub fn write_all(&mut self, data: &[u8]) -> Result<(), WirelessError> {
        // rustls 的 writer 会缓冲明文，必须 flush 才会封装成 TLS 记录，
        // 否则 flush_tls 时 wants_write() 为 false，数据根本不会发出。
        {
            let mut writer = self.conn.writer();
            writer.write_all(data)?;
            writer.flush()?;
        }
        self.flush_tls()
    }

    /// 调整底层 TCP 读超时；用于后台启动命令投递后缩短等待窗口。
    pub fn set_read_timeout(&mut self, dur: Option<Duration>) -> io::Result<()> {
        self.tcp.set_read_timeout(dur)
    }

    /// RFC 5705 导出密钥材料；配对密码 = 配对码字节 || 64 字节导出材料。
    pub fn export_keying_material(
        &self,
        label: &[u8],
        context: Option<&[u8]>,
        out: &mut [u8],
    ) -> Result<(), WirelessError> {
        self.conn
            .export_keying_material(out, label, context)
            .map(|_| ())
            .map_err(WirelessError::Tls)
    }
}

/// 生成 64 字节高质量随机数（Android / Linux 均有 /dev/urandom）。
pub fn random_64() -> Result<[u8; 64], WirelessError> {
    let mut f = std::fs::File::open("/dev/urandom")?;
    let mut out = [0u8; 64];
    f.read_exact(&mut out)?;
    Ok(out)
}
