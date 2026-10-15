use base64::Engine;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct Tls {
    pub cert: Option<String>,
    pub key: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub bind: String,
    pub port: u16,
    pub token: String,
    pub sample_interval: f64,
    pub history_size: usize,
    pub actions: Vec<String>,
    pub disk_mounts: Vec<String>,
    pub tls: Tls,
    pub rate_limit_per_min: u32,
    pub max_body_bytes: usize,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind: "0.0.0.0".into(),
            port: 8765,
            token: String::new(),
            sample_interval: 2.0,
            history_size: 150,
            actions: Vec::new(),
            disk_mounts: vec!["/".into()],
            tls: Tls::default(),
            rate_limit_per_min: 240,
            max_body_bytes: 65536,
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct Overrides {
    pub token: Option<String>,
    pub port: Option<u16>,
    pub bind: Option<String>,
    pub tls_cert: Option<String>,
    pub tls_key: Option<String>,
}

impl Config {
    pub fn load(path: Option<&Path>, overrides: &Overrides) -> Result<Self, String> {
        let mut cfg = match path {
            Some(p) => {
                let text = std::fs::read_to_string(p).map_err(|e| format!("{}: {e}", p.display()))?;
                serde_json::from_str(&text).map_err(|e| format!("{}: {e}", p.display()))?
            }
            None => Self::default(),
        };
        if let Some(v) = &overrides.token {
            cfg.token = v.clone();
        }
        if let Some(v) = overrides.port {
            cfg.port = v;
        }
        if let Some(v) = &overrides.bind {
            cfg.bind = v.clone();
        }
        if let (Some(cert), Some(key)) = (&overrides.tls_cert, &overrides.tls_key) {
            cfg.tls = Tls {
                cert: Some(cert.clone()),
                key: Some(key.clone()),
            };
        }
        if cfg.token.is_empty() {
            cfg.token = std::env::var("SERVEX_TOKEN").unwrap_or_default();
        }
        Ok(cfg)
    }

    pub fn save(&self, path: &Path) -> Result<(), String> {
        if let Some(dir) = path.parent() {
            if !dir.as_os_str().is_empty() {
                std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700));
                }
            }
        }
        let text = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(path, format!("{text}\n")).map_err(|e| format!("{}: {e}", path.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
                .map_err(|e| format!("{}: {e}", path.display()))?;
        }
        Ok(())
    }

    pub fn init(path: &Path, port: Option<u16>, bind: Option<String>) -> Result<Self, String> {
        if path.exists() {
            let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
            let cfg: Self =
                serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
            eprintln!("config already exists at {}; leaving unchanged", path.display());
            return Ok(cfg);
        }
        let mut cfg = Self::default();
        cfg.token = gen_token();
        if let Some(p) = port {
            cfg.port = p;
        }
        if let Some(b) = bind {
            cfg.bind = b;
        }
        cfg.save(path)?;
        Ok(cfg)
    }
}

pub fn gen_token() -> String {
    let mut buf = [0u8; 24];
    rand::rngs::OsRng.fill_bytes(&mut buf);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(buf)
}

pub fn token_ok(provided: &str, expected: &str) -> bool {
    if provided.is_empty() || expected.is_empty() {
        return false;
    }
    let (a, b) = (provided.as_bytes(), expected.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for i in 0..a.len() {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

pub fn default_config_path() -> PathBuf {
    if cfg!(windows) {
        let base = std::env::var("ProgramData").unwrap_or_else(|_| "C:\\ProgramData".into());
        PathBuf::from(base).join("Servex").join("servex-agent.json")
    } else {
        PathBuf::from("/etc/servex/servex-agent.json")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("servex-test-{}-{}", std::process::id(), name))
    }

    #[test]
    fn defaults_match_the_python_agent() {
        let c = Config::default();
        assert_eq!(c.bind, "0.0.0.0");
        assert_eq!(c.port, 8765);
        assert_eq!(c.sample_interval, 2.0);
        assert_eq!(c.history_size, 150);
        assert!(c.actions.is_empty());
        assert_eq!(c.disk_mounts, vec!["/".to_string()]);
        assert_eq!(c.rate_limit_per_min, 240);
        assert_eq!(c.max_body_bytes, 65536);
        assert!(c.tls.cert.is_none());
    }

    #[test]
    fn overrides_win_and_file_is_merged_over_defaults() {
        let path = tmp("load.json");
        std::fs::write(&path, r#"{"port": 9999, "history_size": 5}"#).unwrap();
        let cfg = Config::load(
            Some(&path),
            &Overrides {
                port: Some(1234),
                ..Default::default()
            },
        )
        .unwrap();
        assert_eq!(cfg.port, 1234);
        assert_eq!(cfg.history_size, 5);
        assert_eq!(cfg.bind, "0.0.0.0");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn init_creates_token_and_never_overwrites() {
        let path = tmp("init.json");
        let _ = std::fs::remove_file(&path);
        let cfg = Config::init(&path, Some(9000), None).unwrap();
        assert!(!cfg.token.is_empty());
        assert_eq!(cfg.port, 9000);
        assert!(path.exists());
        std::fs::write(&path, r#"{"token": "keepme"}"#).unwrap();
        let again = Config::init(&path, Some(1), None).unwrap();
        assert_eq!(again.token, "keepme");
        assert_eq!(again.port, 8765);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn tokens_compare_safely() {
        assert!(token_ok("abc", "abc"));
        assert!(!token_ok("abc", "abd"));
        assert!(!token_ok("", "abc"));
        assert!(!token_ok("abc", ""));
        assert!(!token_ok("abcd", "abc"));
    }

    #[test]
    fn generated_tokens_are_url_safe_and_unique() {
        let a = gen_token();
        let b = gen_token();
        assert_ne!(a, b);
        assert_eq!(a.len(), 32);
        assert!(a.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
    }
}
