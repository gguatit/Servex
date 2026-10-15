use crate::server::Agent;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;

pub type Action = Arc<dyn Fn(&Value, &Arc<Agent>) -> Result<String, String> + Send + Sync>;

pub fn builtin_actions() -> HashMap<&'static str, Action> {
    let mut map: HashMap<&'static str, Action> = HashMap::new();
    map.insert("system_info", Arc::new(system_info));
    map.insert("service_restart", Arc::new(service_restart));
    map
}

fn system_info(_args: &Value, agent: &Arc<Agent>) -> Result<String, String> {
    match agent.latest() {
        Some(snap) => Ok(format!(
            "{} {} uptime={}s cpu={}%",
            snap.host, snap.os, snap.uptime_s, snap.cpu.usage_pct
        )),
        None => Ok("None None uptime=0s cpu=0%".into()),
    }
}

fn service_restart(args: &Value, _agent: &Arc<Agent>) -> Result<String, String> {
    let name = args.get("service").and_then(|v| v.as_str()).unwrap_or("");
    if !valid_service_name(name) {
        return Err("invalid service name".into());
    }
    match crate::collect::run_cmd("systemctl", &["restart", name], 30) {
        Some(_) => Ok(format!("restarted {name}")),
        None => Err(format!("failed to restart {name}")),
    }
}

fn valid_service_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 64
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '@' | '-'))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn service_names_are_validated() {
        assert!(valid_service_name("nginx"));
        assert!(valid_service_name("nginx.service"));
        assert!(valid_service_name("user@1000"));
        assert!(!valid_service_name(""));
        assert!(!valid_service_name("nginx; rm -rf /"));
        assert!(!valid_service_name(&"a".repeat(65)));
    }

    #[test]
    fn both_builtin_actions_are_registered() {
        let registry = builtin_actions();
        assert!(registry.contains_key("system_info"));
        assert!(registry.contains_key("service_restart"));
    }
}
