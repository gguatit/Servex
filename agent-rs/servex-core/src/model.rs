use serde::Serialize;

#[derive(Debug, Clone, Serialize, Default)]
pub struct Cpu {
    pub usage_pct: f64,
    pub cores: usize,
    pub threads: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load1: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load5: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub load15: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub per_core: Option<Vec<f64>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temp_c: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Memory {
    pub total: u64,
    pub used: u64,
    pub available: u64,
    pub pct: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub swap_total: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub swap_used: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub swap_pct: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cached: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub buffers: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Disk {
    pub mount: String,
    pub total: u64,
    pub used: u64,
    pub pct: f64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct NetEntry {
    pub iface: String,
    pub rx_bps: u64,
    pub tx_bps: u64,
    pub rx_total: u64,
    pub tx_total: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rx_errs: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tx_errs: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rx_drops: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tx_drops: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct DiskIo {
    pub read_total: u64,
    pub write_total: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub read_bps: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub write_bps: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Proc {
    pub pid: u32,
    pub name: String,
    pub cpu_pct: f64,
    pub mem_pct: f64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Processes {
    pub by_cpu: Vec<Proc>,
    pub by_mem: Vec<Proc>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Services {
    pub failed: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Gpu {
    pub name: String,
    pub util_pct: f64,
    pub mem_pct: f64,
    pub temp_c: f64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Docker {
    pub running: u32,
    pub stopped: u32,
    pub total: u32,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Snapshot {
    pub ts: i64,
    pub host: String,
    pub os: String,
    pub uptime_s: u64,
    pub cpu: Cpu,
    pub memory: Memory,
    pub disks: Vec<Disk>,
    pub network: Vec<NetEntry>,
    pub kernel: String,
    pub arch: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub boot_time: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub users: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub disk_io: Option<DiskIo>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub processes: Option<Processes>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub services: Option<Services>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gpu: Option<Vec<Gpu>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub docker: Option<Docker>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Summary {
    pub host: Option<String>,
    pub os: Option<String>,
    pub uptime_s: u64,
    pub ts: i64,
}

impl Summary {
    pub fn from(snapshot: Option<&Snapshot>) -> Self {
        match snapshot {
            Some(s) => Self {
                host: Some(s.host.clone()),
                os: Some(s.os.clone()),
                uptime_s: s.uptime_s,
                ts: s.ts,
            },
            None => Self::default(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_serializes_expected_keys() {
        let snap = Snapshot::default();
        let value = serde_json::to_value(&snap).unwrap();
        let obj = value.as_object().unwrap();
        for key in ["ts", "host", "os", "uptime_s", "cpu", "memory", "disks", "network", "kernel", "arch"] {
            assert!(obj.contains_key(key), "missing {key}");
        }
        assert!(!obj.contains_key("gpu"));
        assert!(!obj.contains_key("disk_io"));
        assert!(!obj["cpu"].as_object().unwrap().contains_key("load1"));
    }

    #[test]
    fn summary_of_none_is_empty() {
        let s = Summary::from(None);
        let value = serde_json::to_value(&s).unwrap();
        assert_eq!(value["host"], serde_json::Value::Null);
        assert_eq!(value["uptime_s"], 0);
        assert_eq!(value["ts"], 0);
    }
}
