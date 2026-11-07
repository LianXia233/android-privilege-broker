//! 独立 IPC 传输层。
//!
//! 帧格式由 `broker-protocol` 定义（4 字节大端长度 + UTF-8 JSON）。
//! 支持三种承载：Linux abstract Unix socket（Android 首选，无文件系统权限依赖）、
//! 文件路径 Unix socket（host 测试）、TCP loopback（配合无线调试端口转发）。
//! 上层只看到 `Duplex`，与具体承载解耦。

use std::io;
use std::net::SocketAddr;
use std::path::PathBuf;

use broker_protocol::error::ErrorCode;
use broker_protocol::frame::{decode, encode};
use broker_protocol::message::{Envelope, MAX_FRAME_BYTES};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UnixListener, UnixStream};

/// 监听地址。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Endpoint {
    /// Linux/Android abstract namespace，名称为不含前导 NUL 的字符串。
    Abstract(String),
    /// 文件系统路径 Unix socket。
    UnixPath(PathBuf),
    /// TCP loopback。
    Tcp(SocketAddr),
}

impl Endpoint {
    pub fn describe(&self) -> String {
        match self {
            Endpoint::Abstract(n) => format!("abstract:{n}"),
            Endpoint::UnixPath(p) => format!("unix:{}", p.display()),
            Endpoint::Tcp(a) => format!("tcp:{a}"),
        }
    }
}

/// 已绑定的监听器。
pub struct Listener {
    inner: ListenerInner,
    endpoint: Endpoint,
}

enum ListenerInner {
    Abstract(UnixListener),
    UnixPath(UnixListener),
    Tcp(TcpListener),
}

impl Listener {
    pub async fn bind(endpoint: Endpoint) -> io::Result<Self> {
        let inner = match &endpoint {
            Endpoint::Abstract(name) => ListenerInner::Abstract(bind_abstract(name)?),
            Endpoint::UnixPath(path) => {
                if path.exists() {
                    std::fs::remove_file(path)?;
                }
                ListenerInner::UnixPath(UnixListener::bind(path)?)
            }
            Endpoint::Tcp(addr) => ListenerInner::Tcp(TcpListener::bind(addr).await?),
        };
        Ok(Self { inner, endpoint })
    }

    pub fn endpoint(&self) -> &Endpoint {
        &self.endpoint
    }

    /// 实际监听地址（TCP 绑定到端口 0 时回读真实端口）。
    pub fn local_addr(&self) -> io::Result<Option<SocketAddr>> {
        match &self.inner {
            ListenerInner::Tcp(l) => Ok(Some(l.local_addr()?)),
            _ => Ok(None),
        }
    }

    pub async fn accept(&self) -> io::Result<Duplex> {
        let stream = match &self.inner {
            ListenerInner::Abstract(l) | ListenerInner::UnixPath(l) => {
                let (s, _) = l.accept().await?;
                Duplex::Unix(s)
            }
            ListenerInner::Tcp(l) => {
                let (s, _) = l.accept().await?;
                Duplex::Tcp(s)
            }
        };
        Ok(stream)
    }
}

/// 客户端连接。
pub async fn connect(endpoint: &Endpoint) -> io::Result<Duplex> {
    match endpoint {
        Endpoint::Abstract(name) => Ok(Duplex::Unix(connect_abstract(name).await?)),
        Endpoint::UnixPath(path) => Ok(Duplex::Unix(UnixStream::connect(path).await?)),
        Endpoint::Tcp(addr) => Ok(Duplex::Tcp(TcpStream::connect(addr).await?)),
    }
}

/// 双向字节流。
pub enum Duplex {
    Unix(UnixStream),
    Tcp(TcpStream),
}

impl AsyncRead for Duplex {
    fn poll_read(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<io::Result<()>> {
        match self.get_mut() {
            Duplex::Unix(s) => std::pin::Pin::new(s).poll_read(cx, buf),
            Duplex::Tcp(s) => std::pin::Pin::new(s).poll_read(cx, buf),
        }
    }
}

impl AsyncWrite for Duplex {
    fn poll_write(
        self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<io::Result<usize>> {
        match self.get_mut() {
            Duplex::Unix(s) => std::pin::Pin::new(s).poll_write(cx, buf),
            Duplex::Tcp(s) => std::pin::Pin::new(s).poll_write(cx, buf),
        }
    }
    fn poll_flush(self: std::pin::Pin<&mut Self>, cx: &mut std::task::Context<'_>) -> std::task::Poll<io::Result<()>> {
        match self.get_mut() {
            Duplex::Unix(s) => std::pin::Pin::new(s).poll_flush(cx),
            Duplex::Tcp(s) => std::pin::Pin::new(s).poll_flush(cx),
        }
    }
    fn poll_shutdown(self: std::pin::Pin<&mut Self>, cx: &mut std::task::Context<'_>) -> std::task::Poll<io::Result<()>> {
        match self.get_mut() {
            Duplex::Unix(s) => std::pin::Pin::new(s).poll_shutdown(cx),
            Duplex::Tcp(s) => std::pin::Pin::new(s).poll_shutdown(cx),
        }
    }
}

/// 从流上读取一条完整消息。长度字段在分配缓冲区前校验。
pub async fn read_frame<S: AsyncRead + Unpin>(stream: &mut S) -> Result<Envelope, ErrorCode> {
    let mut head = [0u8; 4];
    stream.read_exact(&mut head).await.map_err(|_| ErrorCode::MalformedFrame)?;
    let n = u32::from_be_bytes(head) as usize;
    if n == 0 || n > MAX_FRAME_BYTES {
        return Err(ErrorCode::FrameTooLarge);
    }
    let mut frame = Vec::with_capacity(4 + n);
    frame.extend_from_slice(&head);
    frame.resize(4 + n, 0);
    stream.read_exact(&mut frame[4..]).await.map_err(|_| ErrorCode::MalformedFrame)?;
    decode(&frame)
}

/// 向流写出一条消息。
pub async fn write_frame<S: AsyncWrite + Unpin>(stream: &mut S, env: &Envelope) -> Result<(), ErrorCode> {
    let frame = encode(env)?;
    stream.write_all(&frame).await.map_err(|_| ErrorCode::Internal)?;
    stream.flush().await.map_err(|_| ErrorCode::Internal)?;
    Ok(())
}

/// 构造 abstract namespace 的 `sockaddr_un` 并绑定为 Tokio 监听器。
#[cfg(any(target_os = "linux", target_os = "android"))]
fn bind_abstract(name: &str) -> io::Result<UnixListener> {
    use std::os::fd::FromRawFd;
    if name.is_empty() || name.len() > 106 || name.as_bytes().contains(&0) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid abstract socket name"));
    }
    unsafe {
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
        if fd < 0 { return Err(io::Error::last_os_error()); }
        let mut addr: libc::sockaddr_un = std::mem::zeroed();
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
        // sun_path[0] 保持 0 表示 abstract namespace，名字从索引 1 开始。
        let bytes = name.as_bytes();
        std::ptr::copy_nonoverlapping(
            bytes.as_ptr(),
            addr.sun_path.as_mut_ptr().add(1) as *mut u8,
            bytes.len(),
        );
        let len = (std::mem::size_of::<libc::sa_family_t>() + 1 + bytes.len()) as libc::socklen_t;
        if libc::bind(fd, &addr as *const _ as *const libc::sockaddr, len) != 0 {
            let e = io::Error::last_os_error();
            libc::close(fd);
            return Err(e);
        }
        if libc::listen(fd, 32) != 0 {
            let e = io::Error::last_os_error();
            libc::close(fd);
            return Err(e);
        }
        let std_listener = std::os::unix::net::UnixListener::from_raw_fd(fd);
        std_listener.set_nonblocking(true)?;
        UnixListener::from_std(std_listener)
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
async fn connect_abstract(name: &str) -> io::Result<UnixStream> {
    use std::os::fd::FromRawFd;
    if name.is_empty() || name.len() > 106 || name.as_bytes().contains(&0) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid abstract socket name"));
    }
    unsafe {
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
        if fd < 0 { return Err(io::Error::last_os_error()); }
        let mut addr: libc::sockaddr_un = std::mem::zeroed();
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
        let bytes = name.as_bytes();
        std::ptr::copy_nonoverlapping(
            bytes.as_ptr(),
            addr.sun_path.as_mut_ptr().add(1) as *mut u8,
            bytes.len(),
        );
        let len = (std::mem::size_of::<libc::sa_family_t>() + 1 + bytes.len()) as libc::socklen_t;
        if libc::connect(fd, &addr as *const _ as *const libc::sockaddr, len) != 0 {
            let e = io::Error::last_os_error();
            libc::close(fd);
            return Err(e);
        }
        let std_stream = std::os::unix::net::UnixStream::from_raw_fd(fd);
        std_stream.set_nonblocking(true)?;
        UnixStream::from_std(std_stream)
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn bind_abstract(_name: &str) -> io::Result<UnixListener> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "abstract socket requires Linux/Android"))
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
async fn connect_abstract(_name: &str) -> io::Result<UnixStream> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "abstract socket requires Linux/Android"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use broker_protocol::message::{Body, ResponseStatus, StdinSpec};

    fn sample(id: u64) -> Envelope {
        Envelope::new(id, 1, "client-a", id, 1_000, 5_000, Body::StatusQuery {})
    }

    #[tokio::test]
    async fn frame_roundtrip_over_tcp() {
        let listener = Listener::bind(Endpoint::Tcp("127.0.0.1:0".parse().unwrap())).await.unwrap();
        let addr = listener.local_addr().unwrap().unwrap();
        let server = tokio::spawn(async move {
            let mut s = listener.accept().await.unwrap();
            let e = read_frame(&mut s).await.unwrap();
            write_frame(&mut s, &e.reply(ResponseStatus::Completed, ErrorCode::Ok, "ok", serde_json::json!({}), 1_100, 5_000)).await.unwrap();
        });
        let mut c = connect(&Endpoint::Tcp(addr)).await.unwrap();
        write_frame(&mut c, &sample(7)).await.unwrap();
        let rep = read_frame(&mut c).await.unwrap();
        assert_eq!(rep.request_id, 7);
        assert_eq!(rep.nonce, 8);
        assert!(matches!(rep.body, Body::Response { .. }));
        server.await.unwrap();
    }

    #[tokio::test]
    async fn frame_roundtrip_over_unix_path() {
        let p = std::env::temp_dir().join(format!("broker_ipc_{}.sock", std::process::id()));
        let ep = Endpoint::UnixPath(p.clone());
        let listener = Listener::bind(ep.clone()).await.unwrap();
        let server = tokio::spawn(async move {
            let mut s = listener.accept().await.unwrap();
            let e = read_frame(&mut s).await.unwrap();
            write_frame(&mut s, &e).await.unwrap();
        });
        let mut c = connect(&ep).await.unwrap();
        write_frame(&mut c, &sample(9)).await.unwrap();
        assert_eq!(read_frame(&mut c).await.unwrap(), sample(9));
        server.await.unwrap();
        std::fs::remove_file(&p).ok();
    }

    #[cfg(target_os = "linux")]
    #[tokio::test]
    async fn frame_roundtrip_over_abstract_socket() {
        let name = format!("broker.test.{}", std::process::id());
        let ep = Endpoint::Abstract(name.clone());
        let listener = Listener::bind(ep.clone()).await.unwrap();
        let server = tokio::spawn(async move {
            let mut s = listener.accept().await.unwrap();
            let e = read_frame(&mut s).await.unwrap();
            write_frame(&mut s, &e).await.unwrap();
        });
        let mut c = connect(&ep).await.unwrap();
        write_frame(&mut c, &sample(11)).await.unwrap();
        assert_eq!(read_frame(&mut c).await.unwrap(), sample(11));
        server.await.unwrap();
    }

    #[tokio::test]
    async fn oversized_declared_length_is_rejected_before_alloc() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut s, _) = listener.accept().await.unwrap();
            s.write_all(&((MAX_FRAME_BYTES as u32) + 1).to_be_bytes()).await.unwrap();
            s.flush().await.unwrap();
        });
        let mut c = TcpStream::connect(addr).await.unwrap();
        assert_eq!(read_frame(&mut c).await, Err(ErrorCode::FrameTooLarge));
        server.await.unwrap();
    }

    #[tokio::test]
    async fn truncated_frame_is_reported() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut s, _) = listener.accept().await.unwrap();
            s.write_all(&[0, 0, 0, 50, b'{']).await.unwrap();
            s.shutdown().await.unwrap();
        });
        let mut c = TcpStream::connect(addr).await.unwrap();
        assert_eq!(read_frame(&mut c).await, Err(ErrorCode::MalformedFrame));
        server.await.unwrap();
    }

    #[test]
    fn endpoint_describe_is_stable() {
        assert_eq!(Endpoint::Abstract("abc".into()).describe(), "abstract:abc");
        assert_eq!(Endpoint::Tcp("127.0.0.1:9".parse().unwrap()).describe(), "tcp:127.0.0.1:9");
    }

    #[tokio::test]
    async fn fragmented_and_coalesced_frames_both_work() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let a = sample(1);
        let b = sample(2);
        let fa = encode(&a).unwrap();
        let fb = encode(&b).unwrap();
        let server = tokio::spawn(async move {
            let (mut s, _) = listener.accept().await.unwrap();
            // 第一帧拆三段，第二帧与第一帧尾部粘在一起
            s.write_all(&fa[..2]).await.unwrap();
            tokio::task::yield_now().await;
            s.write_all(&fa[2..]).await.unwrap();
            s.write_all(&fb).await.unwrap();
            s.flush().await.unwrap();
        });
        let mut c = TcpStream::connect(addr).await.unwrap();
        assert_eq!(read_frame(&mut c).await.unwrap(), a);
        assert_eq!(read_frame(&mut c).await.unwrap(), b);
        server.await.unwrap();
    }

    #[test]
    fn exec_body_survives_ipc_encode_decode() {
        let e = Envelope::new(3, 1, "c", 3, 1_000, 5_000, Body::Exec {
            argv: vec!["pm".into(), "install-create".into()],
            stdin: StdinSpec::Path { path: "/data/local/tmp/a.apk".into() },
            env: vec![],
            timeout_ms: 30_000,
            cancel_group: 0,
        });
        assert_eq!(decode(&encode(&e).unwrap()).unwrap(), e);
    }
}
