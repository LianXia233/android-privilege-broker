//! 帧格式：4 字节大端长度前缀 + UTF-8 JSON 载荷。
//!
//! 长度上限在解析前生效，避免对端声明超大长度造成内存压力。

use crate::error::ErrorCode;
use crate::message::{Envelope, MAX_FRAME_BYTES, PROTOCOL_VERSION};

/// 把一条消息编码为完整帧。
pub fn encode(env: &Envelope) -> Result<Vec<u8>, ErrorCode> {
    let payload = serde_json::to_vec(env).map_err(|_| ErrorCode::MalformedFrame)?;
    if payload.len() > MAX_FRAME_BYTES {
        return Err(ErrorCode::FrameTooLarge);
    }
    let mut out = Vec::with_capacity(4 + payload.len());
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(&payload);
    Ok(out)
}

/// 把一段完整字节解析为消息。长度字段必须与载荷严格一致。
pub fn decode(bytes: &[u8]) -> Result<Envelope, ErrorCode> {
    if bytes.len() < 4 {
        return Err(ErrorCode::MalformedFrame);
    }
    let n = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) as usize;
    if n > MAX_FRAME_BYTES {
        return Err(ErrorCode::FrameTooLarge);
    }
    if bytes.len() != 4 + n {
        return Err(ErrorCode::MalformedFrame);
    }
    let env: Envelope =
        serde_json::from_slice(&bytes[4..]).map_err(|_| ErrorCode::MalformedFrame)?;
    if env.version != PROTOCOL_VERSION {
        return Err(ErrorCode::VersionMismatch);
    }
    Ok(env)
}

/// 流式读取器：socket 上字节可能分片到达，由它拼帧并逐条吐出。
pub struct FrameReader {
    buf: Vec<u8>,
}

impl FrameReader {
    pub fn new() -> Self {
        Self { buf: Vec::new() }
    }

    /// 追加收到的字节。累计未消费字节超过上限时拒绝，防止慢速堆积。
    pub fn push(&mut self, chunk: &[u8]) -> Result<(), ErrorCode> {
        if self.buf.len() + chunk.len() > MAX_FRAME_BYTES * 2 {
            return Err(ErrorCode::FrameTooLarge);
        }
        self.buf.extend_from_slice(chunk);
        Ok(())
    }

    /// 取出下一条完整消息；字节不足时返回 Ok(None)，不报错也不阻塞。
    pub fn next_frame(&mut self) -> Result<Option<Envelope>, ErrorCode> {
        if self.buf.len() < 4 {
            return Ok(None);
        }
        let n = u32::from_be_bytes([self.buf[0], self.buf[1], self.buf[2], self.buf[3]]) as usize;
        if n > MAX_FRAME_BYTES {
            return Err(ErrorCode::FrameTooLarge);
        }
        if self.buf.len() < 4 + n {
            return Ok(None);
        }
        let frame: Vec<u8> = self.buf.drain(..4 + n).collect();
        decode(&frame).map(Some)
    }

    pub fn pending_bytes(&self) -> usize {
        self.buf.len()
    }
}

impl Default for FrameReader {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::message::{Body, StdinSpec};
    use serde_json::json;

    fn sample() -> Envelope {
        Envelope::new(
            1, 2, "client-a", 3, 10, 100,
            Body::Exec {
                argv: vec!["pm".into(), "install-create".into(), "-r".into()],
                stdin: StdinSpec::None,
                env: vec![],
                timeout_ms: 5000,
                cancel_group: 1,
            },
        )
    }

    #[test]
    fn encode_decode_roundtrip() {
        let e = sample();
        let frame = encode(&e).unwrap();
        assert!(frame.len() > 4);
        assert_eq!(decode(&frame).unwrap(), e);
    }

    #[test]
    fn frame_roundtrip_through_json_value() {
        let e = sample();
        let frame = encode(&e).unwrap();
        let n = u32::from_be_bytes([frame[0], frame[1], frame[2], frame[3]]) as usize;
        let v: serde_json::Value = serde_json::from_slice(&frame[4..]).unwrap();
        assert_eq!(v["requestId"], json!(1));
        assert_eq!(v["sessionId"], json!(2));
        assert_eq!(v["body"]["type"], json!("exec"));
        assert_eq!(v["body"]["argv"][0], json!("pm"));
        assert_eq!(n, frame.len() - 4);
    }

    #[test]
    fn rejects_truncated_and_mismatched_length() {
        let frame = encode(&sample()).unwrap();
        assert_eq!(decode(&frame[..3]), Err(ErrorCode::MalformedFrame));
        let mut bad = frame.clone();
        bad.truncate(frame.len() - 2);
        assert_eq!(decode(&bad), Err(ErrorCode::MalformedFrame));
    }

    #[test]
    fn rejects_oversized_declared_length() {
        let mut frame = vec![0u8; 4];
        frame[0..4].copy_from_slice(&((MAX_FRAME_BYTES as u32) + 1).to_be_bytes());
        assert_eq!(decode(&frame), Err(ErrorCode::FrameTooLarge));
    }

    #[test]
    fn rejects_unknown_protocol_version() {
        let mut e = sample();
        e.version = PROTOCOL_VERSION + 1;
        // 手工构造载荷，验证 decode 侧对版本字段的独立校验
        let payload = serde_json::to_vec(&e).unwrap();
        let mut frame = Vec::new();
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(&payload);
        assert_eq!(decode(&frame), Err(ErrorCode::VersionMismatch));
    }

    #[test]
    fn reader_handles_fragmented_and_coalesced_frames() {
        let a = sample();
        let b = Envelope::new(9, 2, "client-a", 4, 10, 100, Body::StatusQuery {});
        let fa = encode(&a).unwrap();
        let fb = encode(&b).unwrap();
        let mut r = FrameReader::new();

        // 第一帧分三次到达
        r.push(&fa[..2]).unwrap();
        assert!(r.next_frame().unwrap().is_none());
        r.push(&fa[2..5]).unwrap();
        assert!(r.next_frame().unwrap().is_none());
        r.push(&fa[5..]).unwrap();
        assert_eq!(r.next_frame().unwrap().unwrap(), a);

        // 两帧粘在一起到达
        let mut both = fb.clone();
        both.extend_from_slice(&fa);
        r.push(&both).unwrap();
        assert_eq!(r.next_frame().unwrap().unwrap(), b);
        assert_eq!(r.next_frame().unwrap().unwrap(), a);
        assert!(r.next_frame().unwrap().is_none());
        assert_eq!(r.pending_bytes(), 0);
    }
}
