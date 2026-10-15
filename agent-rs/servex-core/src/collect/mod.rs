use crate::model::*;
use crate::round1;
use crate::Config;
use std::collections::HashMap;

#[cfg(unix)]
pub mod linux;
#[cfg(windows)]
pub mod windows;

#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct CpuCounters {
    pub idle: u64,
    pub total: u64,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct NetCounters {
    pub rx: u64,
    pub tx: u64,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct DiskCounters {
    pub read: u64,
    pub write: u64,
}

#[derive(Debug, Default)]
pub struct State {
    pub prev_cpu: Option<CpuCounters>,
    pub prev_cores: Vec<CpuCounters>,
    pub prev_net: HashMap<String, NetCounters>,
    pub prev_io: Option<DiskCounters>,
}

pub fn now_epoch() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub fn cpu_usage(prev: &CpuCounters, cur: &CpuCounters) -> f64 {
    let d_total = cur.total.saturating_sub(prev.total);
    let d_idle = cur.idle.saturating_sub(prev.idle).min(d_total);
    if d_total == 0 {
        return 0.0;
    }
    round1((d_total - d_idle) as f64 / d_total as f64 * 100.0)
}

pub fn net_rates(
    prev: &HashMap<String, NetCounters>,
    cur: &[(String, NetCounters)],
    dt: f64,
) -> Vec<NetEntry> {
    cur.iter()
        .map(|(iface, c)| {
            let (rx_bps, tx_bps) = match prev.get(iface) {
                Some(p) if dt > 0.0 => (
                    (c.rx.saturating_sub(p.rx) as f64 / dt) as u64,
                    (c.tx.saturating_sub(p.tx) as f64 / dt) as u64,
                ),
                _ => (0, 0),
            };
            NetEntry {
                iface: iface.clone(),
                rx_bps,
                tx_bps,
                rx_total: c.rx,
                tx_total: c.tx,
                ..Default::default()
            }
        })
        .collect()
}

pub fn top_processes(mut procs: Vec<Proc>) -> Processes {
    let mut by_cpu = procs.clone();
    by_cpu.sort_by(|a, b| b.cpu_pct.total_cmp(&a.cpu_pct));
    by_cpu.truncate(5);
    procs.sort_by(|a, b| b.mem_pct.total_cmp(&a.mem_pct));
    procs.truncate(5);
    Processes {
        by_cpu,
        by_mem: procs,
    }
}

pub fn hostname() -> String {
    if cfg!(windows) {
        std::env::var("COMPUTERNAME").unwrap_or_else(|_| "windows".into())
    } else {
        std::fs::read_to_string("/proc/sys/kernel/hostname")
            .map(|s| s.trim().to_string())
            .ok()
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "linux".into())
    }
}

/// Run a helper command, returning its stdout, or None when it is missing,
/// fails, or exceeds the timeout. ponytail: stdout is read after the process
/// exits, so output larger than the OS pipe buffer would deadlock; every caller
/// here prints a handful of lines.
pub fn run_cmd(program: &str, args: &[&str], timeout_secs: u64) -> Option<String> {
    use std::io::Read;
    use std::process::{Command, Stdio};

    let mut cmd = Command::new(program);
    cmd.args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    let mut child = cmd.spawn().ok()?;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(timeout_secs);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                if !status.success() {
                    return None;
                }
                let mut out = String::new();
                child.stdout.take()?.read_to_string(&mut out).ok()?;
                return Some(out);
            }
            Ok(None) => {
                if std::time::Instant::now() > deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            Err(_) => return None,
        }
    }
}

pub fn sample(state: &mut State, cfg: &Config, dt: f64) -> Snapshot {
    let mut snap = Snapshot {
        ts: now_epoch(),
        host: hostname(),
        ..Default::default()
    };
    #[cfg(unix)]
    linux::collect(&mut snap, state, cfg, dt);
    #[cfg(windows)]
    windows::collect(&mut snap, state, cfg, dt);
    snap
}

pub fn gpu() -> Option<Vec<Gpu>> {
    let out = run_cmd(
        "nvidia-smi",
        &[
            "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
            "--format=csv,noheader,nounits",
        ],
        5,
    )?;
    let mut gpus = Vec::new();
    for line in out.lines() {
        let parts: Vec<&str> = line.split(',').map(|p| p.trim()).collect();
        if parts.len() < 5 {
            continue;
        }
        let (Ok(util), Ok(mem_used), Ok(mem_total), Ok(temp)) = (
            parts[1].parse::<f64>(),
            parts[2].parse::<f64>(),
            parts[3].parse::<f64>(),
            parts[4].parse::<f64>(),
        ) else {
            continue;
        };
        gpus.push(Gpu {
            name: parts[0].to_string(),
            util_pct: round1(util),
            mem_pct: if mem_total > 0.0 {
                round1(mem_used / mem_total * 100.0)
            } else {
                0.0
            },
            temp_c: round1(temp),
        });
    }
    if gpus.is_empty() {
        None
    } else {
        Some(gpus)
    }
}

pub fn docker() -> Option<Docker> {
    let out = run_cmd("docker", &["ps", "-a", "--format", "{{.State}}"], 5)?;
    let mut docker = Docker::default();
    for line in out.lines() {
        let state = line.trim().to_lowercase();
        if state.is_empty() {
            continue;
        }
        docker.total += 1;
        if state == "running" {
            docker.running += 1;
        } else {
            docker.stopped += 1;
        }
    }
    Some(docker)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn counters(idle: u64, total: u64) -> CpuCounters {
        CpuCounters { idle, total }
    }

    #[test]
    fn cpu_usage_is_a_clamped_delta() {
        assert_eq!(cpu_usage(&counters(100, 200), &counters(150, 300)), 50.0);
        assert_eq!(cpu_usage(&counters(100, 200), &counters(100, 200)), 0.0);
        assert_eq!(cpu_usage(&counters(0, 0), &counters(0, 0)), 0.0);
        assert_eq!(cpu_usage(&counters(200, 300), &counters(100, 300)), 0.0);
        assert_eq!(cpu_usage(&counters(0, 100), &counters(100, 100)), 0.0);
    }

    #[test]
    fn net_rates_handle_first_sample_and_counter_resets() {
        let cur = vec![("eth0".to_string(), NetCounters { rx: 2048, tx: 1024 })];
        let first = net_rates(&HashMap::new(), &cur, 2.0);
        assert_eq!(first[0].rx_bps, 0);
        assert_eq!(first[0].rx_total, 2048);

        let mut prev = HashMap::new();
        prev.insert("eth0".to_string(), NetCounters { rx: 1024, tx: 0 });
        let normal = net_rates(&prev, &cur, 2.0);
        assert_eq!(normal[0].rx_bps, 512);
        assert_eq!(normal[0].tx_bps, 512);

        prev.insert("eth0".to_string(), NetCounters { rx: 9999, tx: 9999 });
        let reset = net_rates(&prev, &cur, 2.0);
        assert_eq!(reset[0].rx_bps, 0);
        assert_eq!(reset[0].tx_bps, 0);

        let zero_dt = net_rates(&prev, &cur, 0.0);
        assert_eq!(zero_dt[0].rx_bps, 0);
    }

    #[test]
    fn top_processes_rank_by_cpu_and_memory() {
        let procs = vec![
            Proc { pid: 1, name: "a".into(), cpu_pct: 1.0, mem_pct: 50.0 },
            Proc { pid: 2, name: "b".into(), cpu_pct: 90.0, mem_pct: 1.0 },
        ];
        let top = top_processes(procs);
        assert_eq!(top.by_cpu[0].pid, 2);
        assert_eq!(top.by_mem[0].pid, 1);
    }

    #[test]
    fn hostname_is_not_empty() {
        assert!(!hostname().is_empty());
    }

    #[test]
    fn run_cmd_reports_missing_programs() {
        assert!(run_cmd("servex-definitely-not-a-program", &[], 1).is_none());
    }
}
