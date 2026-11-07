use crate::error::{CoreError, Result};
use crate::model::{ArchiveEntry, SplitPart};
use crate::platform::StdinSource;
use crate::util::normalize_split_name;

/// 从归档条目清单中挑选 split APK 并生成有序的写入计划。
///
/// 业务规则（本实现自主定义）：
/// 1. 仅接受 `.apk` 条目且大小非零；
/// 2. `base.apk` 必须排第一，其余按名称稳定排序；
/// 3. split 名去重，避免 `pm install-write` 覆盖同一槽位；
/// 4. 字节流始终来自归档条目，不落盘。
pub fn parts_from_archive(uri: &str, entries: &[ArchiveEntry]) -> Result<Vec<SplitPart>> {
    let mut apks: Vec<&ArchiveEntry> = entries
        .iter()
        .filter(|e| e.name.to_ascii_lowercase().ends_with(".apk") && e.size > 0)
        .collect();
    if apks.is_empty() {
        return Err(CoreError::Archive("归档中未找到任何 .apk 条目".into()));
    }
    apks.sort_by(|a, b| {
        let rank = |n: &str| -> u8 {
            let l = n.to_ascii_lowercase();
            let leaf = l.rsplit('/').next().unwrap_or(&l);
            if leaf == "base.apk" { 0 } else if leaf.contains("base") { 1 } else { 2 }
        };
        rank(&a.name).cmp(&rank(&b.name)).then_with(|| a.name.cmp(&b.name))
    });

    let mut used: Vec<String> = Vec::new();
    let mut parts = Vec::with_capacity(apks.len());
    for (i, e) in apks.iter().enumerate() {
        let name = normalize_split_name(&e.name, i, &used);
        used.push(name.clone());
        parts.push(SplitPart {
            name,
            size: e.size,
            stdin: StdinSource::ArchiveEntry {
                uri: uri.to_string(),
                entry: e.name.clone(),
            },
        });
    }
    Ok(parts)
}

/// 单 APK / 多 APK 直传时生成计划。
pub fn parts_from_sources(sources: &[(String, String, u64)]) -> Result<Vec<SplitPart>> {
    if sources.is_empty() {
        return Err(CoreError::InvalidArgument("没有选择任何安装包".into()));
    }
    let mut used: Vec<String> = Vec::new();
    let mut parts = Vec::with_capacity(sources.len());
    for (i, (uri, display, size)) in sources.iter().enumerate() {
        let raw = if sources.len() == 1 && !display.is_empty() {
            display.clone()
        } else {
            format!("split_{i}.apk")
        };
        let name = normalize_split_name(&raw, i, &used);
        used.push(name.clone());
        parts.push(SplitPart {
            name,
            size: *size,
            stdin: StdinSource::Uri { uri: uri.clone() },
        });
    }
    // 单个输入强制命名为 base.apk，符合 PackageInstaller 约定。
    if parts.len() == 1 {
        parts[0].name = "base.apk".to_string();
    }
    Ok(parts)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn archive_base_first_and_only_apks() {
        let entries = vec![
            ArchiveEntry { name: "split_config.arm64_v8a.apk".into(), size: 10 },
            ArchiveEntry { name: "README.txt".into(), size: 5 },
            ArchiveEntry { name: "base.apk".into(), size: 100 },
            ArchiveEntry { name: "split_xxhdpi.apk".into(), size: 20 },
        ];
        let p = parts_from_archive("content://x", &entries).unwrap();
        assert_eq!(p.len(), 3);
        assert_eq!(p[0].name, "base.apk");
        assert_eq!(p[0].size, 100);
        assert!(matches!(&p[1].stdin, StdinSource::ArchiveEntry { entry, .. } if entry.starts_with("split_")));
    }

    #[test]
    fn archive_without_apk_is_error() {
        assert!(parts_from_archive("u", &[ArchiveEntry { name: "a.txt".into(), size: 1 }]).is_err());
    }

    #[test]
    fn single_source_is_named_base() {
        let p = parts_from_sources(&[("content://a".into(), "foo.apk".into(), 7)]).unwrap();
        assert_eq!(p[0].name, "base.apk");
        assert_eq!(p[0].size, 7);
    }
}
