//! 会话生命周期：创建、过期、撤销、多客户端隔离与资源回收。

use std::collections::{HashMap, HashSet};

use crate::error::ErrorCode;
use crate::message::BackendKind;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Session {
    pub id: u64,
    pub client_id: String,
    pub uid: i32,
    pub backend: BackendKind,
    pub created_at_ms: u64,
    pub expires_at_ms: u64,
    pub revoked: bool,
    /// 该会话下仍在运行的进程句柄，会话失效时统一回收。
    pub active_handles: HashSet<u64>,
}

impl Session {
    pub fn is_live(&self, now_ms: u64) -> bool {
        !self.revoked && now_ms <= self.expires_at_ms
    }
}

pub struct SessionTable {
    sessions: HashMap<u64, Session>,
    next_id: u64,
    ttl_ms: u64,
}

impl SessionTable {
    pub fn new(ttl_ms: u64) -> Self {
        Self {
            sessions: HashMap::new(),
            next_id: 1,
            ttl_ms,
        }
    }

    /// 为已通过认证的客户端建立会话。uid 与后端类型由服务端实测后传入。
    pub fn open(
        &mut self,
        client_id: &str,
        uid: i32,
        backend: BackendKind,
        now_ms: u64,
    ) -> u64 {
        let id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1).max(1);
        self.sessions.insert(
            id,
            Session {
                id,
                client_id: client_id.to_string(),
                uid,
                backend,
                created_at_ms: now_ms,
                expires_at_ms: now_ms.saturating_add(self.ttl_ms),
                revoked: false,
                active_handles: HashSet::new(),
            },
        );
        id
    }

    /// 取会话：必须同时满足存在、未撤销、未过期、客户端一致。
    pub fn get(&self, id: u64, client_id: &str, now_ms: u64) -> Result<&Session, ErrorCode> {
        let s = self.sessions.get(&id).ok_or(ErrorCode::SessionUnknown)?;
        if s.revoked {
            return Err(ErrorCode::SessionRevoked);
        }
        if now_ms > s.expires_at_ms {
            return Err(ErrorCode::SessionExpired);
        }
        if s.client_id != client_id {
            // 多客户端隔离：不能借用别人的会话。
            return Err(ErrorCode::PermissionDenied);
        }
        Ok(s)
    }

    /// 续期：活跃会话在每次请求后延长有效期。
    pub fn touch(&mut self, id: u64, client_id: &str, now_ms: u64) -> Result<(), ErrorCode> {
        self.get(id, client_id, now_ms)?;
        if let Some(s) = self.sessions.get_mut(&id) {
            s.expires_at_ms = now_ms.saturating_add(self.ttl_ms);
        }
        Ok(())
    }

    /// 登记进程句柄，供会话失效时回收。
    pub fn attach_handle(
        &mut self,
        id: u64,
        client_id: &str,
        handle: u64,
        now_ms: u64,
    ) -> Result<(), ErrorCode> {
        self.get(id, client_id, now_ms)?;
        if let Some(s) = self.sessions.get_mut(&id) {
            s.active_handles.insert(handle);
        }
        Ok(())
    }

    pub fn detach_handle(&mut self, id: u64, handle: u64) {
        if let Some(s) = self.sessions.get_mut(&id) {
            s.active_handles.remove(&handle);
        }
    }

    /// 授权撤销：会话立即失效，返回待回收的进程句柄列表。
    pub fn revoke(&mut self, id: u64) -> Result<Vec<u64>, ErrorCode> {
        let s = self.sessions.get_mut(&id).ok_or(ErrorCode::SessionUnknown)?;
        s.revoked = true;
        Ok(s.active_handles.drain().collect())
    }

    /// 清理过期会话，返回其中残留的进程句柄。
    pub fn purge(&mut self, now_ms: u64) -> Vec<u64> {
        let expired: Vec<u64> = self
            .sessions
            .iter()
            .filter(|(_, s)| s.revoked || now_ms > s.expires_at_ms)
            .map(|(id, _)| *id)
            .collect();
        let mut handles = Vec::new();
        for id in expired {
            if let Some(mut s) = self.sessions.remove(&id) {
                handles.extend(s.active_handles.drain());
            }
        }
        handles
    }

    pub fn live_count(&self, now_ms: u64) -> usize {
        self.sessions
            .values()
            .filter(|s| s.is_live(now_ms))
            .count()
    }

    pub fn total_count(&self) -> usize {
        self.sessions.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_and_get_session() {
        let mut t = SessionTable::new(10_000);
        let id = t.open("client-a", 2000, BackendKind::AdbShell, 1000);
        let s = t.get(id, "client-a", 1000).unwrap();
        assert_eq!(s.uid, 2000);
        assert_eq!(s.backend, BackendKind::AdbShell);
        assert_eq!(s.expires_at_ms, 11_000);
        assert_eq!(t.live_count(1000), 1);
    }

    #[test]
    fn session_expires_and_can_be_touched() {
        let mut t = SessionTable::new(1000);
        let id = t.open("client-a", 2000, BackendKind::AdbShell, 0);
        assert_eq!(t.get(id, "client-a", 1001), Err(ErrorCode::SessionExpired));

        let mut t2 = SessionTable::new(1000);
        let id2 = t2.open("client-a", 2000, BackendKind::AdbShell, 0);
        t2.touch(id2, "client-a", 500).unwrap();
        assert!(t2.get(id2, "client-a", 1400).is_ok());
    }

    #[test]
    fn cross_client_access_is_denied() {
        let mut t = SessionTable::new(10_000);
        let id = t.open("client-a", 2000, BackendKind::AdbShell, 1000);
        assert_eq!(
            t.get(id, "client-b", 1000),
            Err(ErrorCode::PermissionDenied)
        );
    }

    #[test]
    fn revoke_invalidates_immediately_and_returns_handles() {
        let mut t = SessionTable::new(10_000);
        let id = t.open("client-a", 2000, BackendKind::AdbShell, 1000);
        t.attach_handle(id, "client-a", 31, 1000).unwrap();
        t.attach_handle(id, "client-a", 32, 1000).unwrap();
        let mut handles = t.revoke(id).unwrap();
        handles.sort_unstable();
        assert_eq!(handles, vec![31, 32]);
        assert_eq!(t.get(id, "client-a", 1001), Err(ErrorCode::SessionRevoked));
    }

    #[test]
    fn purge_returns_orphaned_handles() {
        let mut t = SessionTable::new(1000);
        let id = t.open("client-a", 2000, BackendKind::AdbShell, 0);
        t.attach_handle(id, "client-a", 77, 0).unwrap();
        let handles = t.purge(2000);
        assert_eq!(handles, vec![77]);
        assert_eq!(t.total_count(), 0);
    }

    #[test]
    fn unknown_session_is_reported() {
        let mut t = SessionTable::new(1000);
        assert_eq!(t.get(999, "client-a", 0), Err(ErrorCode::SessionUnknown));
        assert_eq!(t.revoke(999), Err(ErrorCode::SessionUnknown));
    }
}
