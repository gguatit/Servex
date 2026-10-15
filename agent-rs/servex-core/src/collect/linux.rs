use super::{
    cpu_usage, docker, gpu, net_rates, now_epoch, run_cmd, top_processes, CpuCounters,
    DiskCounters, NetCounters, State,
};
use crate::model::*;
use crate::round1;
use crate::Config;
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

pub fn collect(snap: &mut Snapshot, st: &mut State, cfg: &Config, dt: f64) {
    snap.os = fs::read_to_string("/etc/os-release")
        .map(|t| read_os_pretty(&t))
        .unwrap_or_else(|_| "Linux".into());
    snap.kernel = fs::read_to_string("/proc/sys/kernel/osrelease")
        .map(|s| s.trim().to_string())
        .unwrap_or_default();
    snap.arch = std::env::consts::ARCH.to_string();

    let stat_text = fs::read_to_string("/proc/stat").ok();
    let mut cur_cpu = CpuCounters::default();
    let mut cur_cores: Vec<CpuCounters> = Vec::new();
    match &stat_text {
        Some(text) => {
            if let Some(c) = parse_proc_stat(text) {
                cur_cpu = c;
            }
            cur_cores = parse_proc_stat_cores(text);
            if let Some(btime) = parse_proc_btime(text) {
                snap.boot_time = Some(btime);
            }
        }
        None => cur_cpu = st.prev_cpu.unwrap_or_default(),
    }

    let uptime_s = fs::read_to_string("/proc/uptime")
        .map(|t| parse_proc_uptime(&t))
        .unwrap_or(0);
    snap.uptime_s = uptime_s;
    if snap.boot_time.is_none() && uptime_s > 0 {
        snap.boot_time = Some(now_epoch() - uptime_s as i64);
    }

    let mut cur_net: Vec<(String, NetCounters)> = Vec::new();
    let mut net_extras: HashMap<String, (u64, u64, u64, u64)> = HashMap::new();
    if let Ok(text) = fs::read_to_string("/proc/net/dev") {
        cur_net = parse_net_dev(&text);
        net_extras = parse_net_dev_extras(&text);
    }
    let mut network = net_rates(&st.prev_net, &cur_net, dt);
    for entry in network.iter_mut() {
        if let Some((rx_errs, rx_drops, tx_errs, tx_drops)) = net_extras.get(&entry.iface) {
            entry.rx_errs = Some(*rx_errs);
            entry.rx_drops = Some(*rx_drops);
            entry.tx_errs = Some(*tx_errs);
            entry.tx_drops = Some(*tx_drops);
        }
    }
    snap.network = network;

    if let Ok(text) = fs::read_to_string("/proc/meminfo") {
        let mut mem = parse_meminfo(&text);
        let extras = parse_meminfo_extras(&text);
        mem.swap_total = Some(extras.swap_total);
        mem.swap_used = Some(extras.swap_used);
        mem.swap_pct = Some(extras.swap_pct);
        mem.cached = Some(extras.cached);
        mem.buffers = Some(extras.buffers);
        snap.memory = mem;
    }

    let cores = num_cpus();
    let mut cpu = Cpu {
        usage_pct: st.prev_cpu.map(|p| cpu_usage(&p, &cur_cpu)).unwrap_or(0.0),
        cores,
        threads: cores,
        ..Default::default()
    };
    let mut loads = [0f64; 3];
    if unsafe { libc::getloadavg(loads.as_mut_ptr(), 3) } == 3 {
        cpu.load1 = Some(round2(loads[0]));
        cpu.load5 = Some(round2(loads[1]));
        cpu.load15 = Some(round2(loads[2]));
    }
    if !cur_cores.is_empty() {
        cpu.per_core = Some(if st.prev_cores.len() == cur_cores.len() {
            st.prev_cores
                .iter()
                .zip(&cur_cores)
                .map(|(p, c)| cpu_usage(p, c))
                .collect()
        } else {
            vec![0.0; cur_cores.len()]
        });
    }
    if let Ok(text) = fs::read_to_string("/proc/cpuinfo") {
        if let Some(model) = parse_cpuinfo_model(&text) {
            cpu.model = Some(model);
        }
    }
    if let Some(temp) = read_thermal_temp() {
        cpu.temp_c = Some(temp);
    }
    snap.cpu = cpu;

    snap.disks = cfg
        .disk_mounts
        .iter()
        .filter_map(|mount| disk_usage(mount))
        .collect();

    if let Ok(text) = fs::read_to_string("/proc/diskstats") {
        let cur = parse_diskstats(&text);
        let mut io = DiskIo {
            read_total: cur.read,
            write_total: cur.write,
            ..Default::default()
        };
        if let Some(prev) = st.prev_io {
            if dt > 0.0 {
                io.read_bps = Some((cur.read.saturating_sub(prev.read) as f64 / dt) as u64);
                io.write_bps = Some((cur.write.saturating_sub(prev.write) as f64 / dt) as u64);
            }
        }
        st.prev_io = Some(cur);
        snap.disk_io = Some(io);
    }

    let procs = linux_processes(snap.memory.total, uptime_s);
    if !procs.is_empty() {
        snap.processes = Some(top_processes(procs));
    }
    if let Some(services) = services() {
        snap.services = Some(services);
    }
    if let Some(gpu) = gpu() {
        snap.gpu = Some(gpu);
    }
    if let Some(docker) = docker() {
        snap.docker = Some(docker);
    }
    if let Some(users) = users() {
        snap.users = Some(users);
    }

    st.prev_cpu = Some(cur_cpu);
    st.prev_cores = cur_cores;
    st.prev_net = cur_net.into_iter().collect();
}

fn round2(v: f64) -> f64 {
    (v * 100.0).round() / 100.0
}

fn num_cpus() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1)
}

pub fn parse_proc_stat(text: &str) -> Option<CpuCounters> {
    for line in text.lines() {
        let Some(rest) = line.strip_prefix("cpu ") else {
            continue;
        };
        let nums: Vec<u64> = rest.split_whitespace().filter_map(|x| x.parse().ok()).collect();
        if nums.len() < 4 {
            return None;
        }
        let idle = nums[3] + nums.get(4).copied().unwrap_or(0);
        return Some(CpuCounters {
            idle,
            total: nums.iter().sum(),
        });
    }
    None
}

pub fn parse_proc_stat_cores(text: &str) -> Vec<CpuCounters> {
    let mut cores = Vec::new();
    for line in text.lines() {
        let mut parts = line.split_whitespace();
        let Some(name) = parts.next() else { continue };
        let Some(index) = name.strip_prefix("cpu") else {
            continue;
        };
        if index.is_empty() || !index.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        let nums: Vec<u64> = parts.filter_map(|x| x.parse().ok()).collect();
        if nums.len() < 4 {
            continue;
        }
        let idle = nums[3] + nums.get(4).copied().unwrap_or(0);
        cores.push(CpuCounters {
            idle,
            total: nums.iter().sum(),
        });
    }
    cores
}

pub fn parse_proc_btime(text: &str) -> Option<i64> {
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("btime ") {
            return rest.split_whitespace().next().and_then(|x| x.parse().ok());
        }
    }
    None
}

fn meminfo_kb(text: &str) -> HashMap<String, u64> {
    let mut vals = HashMap::new();
    for line in text.lines() {
        let Some((key, raw)) = line.split_once(':') else {
            continue;
        };
        if let Some(first) = raw.split_whitespace().next() {
            if let Ok(v) = first.parse::<u64>() {
                vals.insert(key.to_string(), v * 1024);
            }
        }
    }
    vals
}

pub fn parse_meminfo(text: &str) -> Memory {
    let vals = meminfo_kb(text);
    let total = vals.get("MemTotal").copied().unwrap_or(0);
    let available = vals
        .get("MemAvailable")
        .or_else(|| vals.get("MemFree"))
        .copied()
        .unwrap_or(0);
    let used = total.saturating_sub(available);
    Memory {
        total,
        used,
        available,
        pct: if total > 0 {
            round1(used as f64 / total as f64 * 100.0)
        } else {
            0.0
        },
        ..Default::default()
    }
}

#[derive(Debug, Clone, Copy, Default)]
pub struct MemExtras {
    pub swap_total: u64,
    pub swap_used: u64,
    pub swap_pct: f64,
    pub cached: u64,
    pub buffers: u64,
}

pub fn parse_meminfo_extras(text: &str) -> MemExtras {
    let vals = meminfo_kb(text);
    let swap_total = vals.get("SwapTotal").copied().unwrap_or(0);
    let swap_free = vals.get("SwapFree").copied().unwrap_or(0);
    let swap_used = swap_total.saturating_sub(swap_free);
    MemExtras {
        swap_total,
        swap_used,
        swap_pct: if swap_total > 0 {
            round1(swap_used as f64 / swap_total as f64 * 100.0)
        } else {
            0.0
        },
        cached: vals.get("Cached").copied().unwrap_or(0),
        buffers: vals.get("Buffers").copied().unwrap_or(0),
    }
}

pub fn parse_net_dev(text: &str) -> Vec<(String, NetCounters)> {
    let mut out = Vec::new();
    for line in text.lines().skip(2) {
        let Some((iface, rest)) = line.split_once(':') else {
            continue;
        };
        let iface = iface.trim();
        let fields: Vec<&str> = rest.split_whitespace().collect();
        if iface == "lo" || fields.len() < 9 {
            continue;
        }
        let (Ok(rx), Ok(tx)) = (fields[0].parse::<u64>(), fields[8].parse::<u64>()) else {
            continue;
        };
        out.push((iface.to_string(), NetCounters { rx, tx }));
    }
    out
}

pub fn parse_net_dev_extras(text: &str) -> HashMap<String, (u64, u64, u64, u64)> {
    let mut out = HashMap::new();
    for line in text.lines().skip(2) {
        let Some((iface, rest)) = line.split_once(':') else {
            continue;
        };
        let iface = iface.trim();
        let fields: Vec<&str> = rest.split_whitespace().collect();
        if iface == "lo" || fields.len() < 12 {
            continue;
        }
        let (Ok(rx_errs), Ok(rx_drops), Ok(tx_errs), Ok(tx_drops)) = (
            fields[2].parse::<u64>(),
            fields[3].parse::<u64>(),
            fields[10].parse::<u64>(),
            fields[11].parse::<u64>(),
        ) else {
            continue;
        };
        out.insert(iface.to_string(), (rx_errs, rx_drops, tx_errs, tx_drops));
    }
    out
}

pub fn parse_diskstats(text: &str) -> DiskCounters {
    const SECTOR_SIZE: u64 = 512;
    let mut out = DiskCounters::default();
    for line in text.lines() {
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.len() < 10 {
            continue;
        }
        if let (Ok(read), Ok(write)) = (fields[5].parse::<u64>(), fields[9].parse::<u64>()) {
            out.read += read * SECTOR_SIZE;
            out.write += write * SECTOR_SIZE;
        }
    }
    out
}

pub fn parse_proc_uptime(text: &str) -> u64 {
    text.split_whitespace()
        .next()
        .and_then(|x| x.parse::<f64>().ok())
        .map(|v| v.max(0.0) as u64)
        .unwrap_or(0)
}

pub fn read_os_pretty(text: &str) -> String {
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("PRETTY_NAME=") {
            return rest.trim().trim_matches('"').to_string();
        }
    }
    "Linux".into()
}

pub fn parse_cpuinfo_model(text: &str) -> Option<String> {
    for line in text.lines() {
        let Some((key, val)) = line.split_once(':') else {
            continue;
        };
        if matches!(key.trim(), "model name" | "Hardware" | "Processor") {
            let val = val.trim();
            if !val.is_empty() {
                return Some(val.to_string());
            }
        }
    }
    None
}

fn read_thermal_temp() -> Option<f64> {
    let mut paths: Vec<PathBuf> = Vec::new();
    if let Ok(entries) = fs::read_dir("/sys/class/thermal") {
        for entry in entries.flatten() {
            if entry.file_name().to_string_lossy().starts_with("thermal_zone") {
                paths.push(entry.path().join("temp"));
            }
        }
    }
    if let Ok(entries) = fs::read_dir("/sys/class/hwmon") {
        for entry in entries.flatten() {
            if entry.file_name().to_string_lossy().starts_with("hwmon") {
                paths.push(entry.path().join("temp1_input"));
            }
        }
    }
    paths.sort();
    for path in paths {
        let Ok(text) = fs::read_to_string(&path) else {
            continue;
        };
        let Ok(mut value) = text.trim().parse::<f64>() else {
            continue;
        };
        if value > 1000.0 {
            value /= 1000.0;
        }
        if (-50.0..=200.0).contains(&value) {
            return Some(round1(value));
        }
    }
    None
}

fn clock_ticks() -> u64 {
    let ticks = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
    if ticks > 0 {
        ticks as u64
    } else {
        100
    }
}

fn linux_processes(mem_total: u64, uptime_s: u64) -> Vec<Proc> {
    let total_ticks = (uptime_s as f64 * clock_ticks() as f64).max(1.0);
    let Ok(entries) = fs::read_dir("/proc") else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for entry in entries.flatten() {
        let Ok(pid) = entry.file_name().to_string_lossy().parse::<u32>() else {
            continue;
        };
        let Ok(stat) = fs::read_to_string(entry.path().join("stat")) else {
            continue;
        };
        let (Some(lp), Some(rp)) = (stat.find('('), stat.rfind(')')) else {
            continue;
        };
        if lp >= rp {
            continue;
        }
        let name = stat[lp + 1..rp].to_string();
        let fields: Vec<&str> = stat[rp + 1..].split_whitespace().collect();
        let (Some(Ok(utime)), Some(Ok(stime))) = (
            fields.get(11).map(|x| x.parse::<u64>()),
            fields.get(12).map(|x| x.parse::<u64>()),
        ) else {
            continue;
        };
        let mut rss = 0u64;
        if let Ok(status) = fs::read_to_string(entry.path().join("status")) {
            for line in status.lines() {
                if let Some(rest) = line.strip_prefix("VmRSS:") {
                    rss = rest
                        .split_whitespace()
                        .next()
                        .and_then(|x| x.parse::<u64>().ok())
                        .unwrap_or(0)
                        * 1024;
                    break;
                }
            }
        }
        out.push(Proc {
            pid,
            name,
            cpu_pct: round1((utime + stime) as f64 / total_ticks * 100.0),
            mem_pct: if mem_total > 0 {
                round1(rss as f64 / mem_total as f64 * 100.0)
            } else {
                0.0
            },
        });
    }
    out
}

fn disk_usage(mount: &str) -> Option<Disk> {
    let path = std::ffi::CString::new(mount).ok()?;
    let mut stat: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(path.as_ptr(), &mut stat) } != 0 {
        return None;
    }
    let total = stat.f_frsize as u64 * stat.f_blocks as u64;
    let free = stat.f_frsize as u64 * stat.f_bavail as u64;
    let used = total.saturating_sub(free);
    Some(Disk {
        mount: mount.to_string(),
        total,
        used,
        pct: if total > 0 {
            round1(used as f64 / total as f64 * 100.0)
        } else {
            0.0
        },
    })
}

fn users() -> Option<usize> {
    let out = run_cmd("who", &[], 5)?;
    Some(out.lines().filter(|l| !l.trim().is_empty()).count())
}

fn services() -> Option<Services> {
    let failed = run_cmd("systemctl", &["--failed", "--no-legend", "--plain"], 5)?;
    let names: Vec<String> = failed
        .lines()
        .filter_map(|line| line.split_whitespace().next())
        .map(|name| name.trim_start_matches('\u{25cf}').trim().to_string())
        .filter(|name| !name.is_empty())
        .collect();
    let mut services = Services {
        failed: names,
        total: None,
    };
    if let Some(out) = run_cmd("systemctl", &["list-units", "--type=service", "--no-legend"], 5) {
        services.total = Some(out.lines().filter(|l| !l.trim().is_empty()).count());
    }
    Some(services)
}

#[cfg(test)]
mod tests {
    use super::*;

    const PROC_STAT: &str = "cpu  100 0 50 1000 20 0 0 0 0 0\ncpu0 50 0 25 500 10 0 0 0 0 0\ncpu1 50 0 25 500 10 0 0 0 0 0\nintr 0\nbtime 1700000000\n";

    #[test]
    fn proc_stat_parses_aggregate_and_cores() {
        let cpu = parse_proc_stat(PROC_STAT).unwrap();
        assert_eq!(cpu.idle, 1020);
        assert_eq!(cpu.total, 1170);
        let cores = parse_proc_stat_cores(PROC_STAT);
        assert_eq!(cores.len(), 2);
        assert_eq!(cores[0].idle, 510);
        assert_eq!(cores[0].total, 585);
        assert_eq!(parse_proc_btime(PROC_STAT), Some(1700000000));
    }

    #[test]
    fn proc_stat_without_aggregate_line_is_none() {
        assert!(parse_proc_stat("cpu0 1 2 3 4\n").is_none());
    }

    #[test]
    fn meminfo_computes_usage() {
        let text = "MemTotal:       1000 kB\nMemFree:         200 kB\nMemAvailable:    400 kB\nSwapTotal:       500 kB\nSwapFree:        300 kB\nCached:          100 kB\nBuffers:          50 kB\n";
        let mem = parse_meminfo(text);
        assert_eq!(mem.total, 1_024_000);
        assert_eq!(mem.available, 409_600);
        assert_eq!(mem.used, 614_400);
        assert_eq!(mem.pct, 60.0);
        let extras = parse_meminfo_extras(text);
        assert_eq!(extras.swap_total, 512_000);
        assert_eq!(extras.swap_used, 204_800);
        assert_eq!(extras.swap_pct, 40.0);
        assert_eq!(extras.cached, 102_400);
        assert_eq!(extras.buffers, 51_200);
    }

    #[test]
    fn meminfo_falls_back_to_memfree() {
        let mem = parse_meminfo("MemTotal: 100 kB\nMemFree: 25 kB\n");
        assert_eq!(mem.available, 25_600);
    }

    #[test]
    fn net_dev_skips_loopback() {
        let text = "Inter-|   Receive                                                |  Transmit\n face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n    lo: 999 1 0 0 0 0 0 0 999 1 0 0 0 0 0 0\n  eth0: 2048 1 3 4 0 0 0 0 1024 1 5 6 0 0 0 0\n";
        let devs = parse_net_dev(text);
        assert_eq!(devs.len(), 1);
        assert_eq!(devs[0].0, "eth0");
        assert_eq!(devs[0].1.rx, 2048);
        assert_eq!(devs[0].1.tx, 1024);
        let extras = parse_net_dev_extras(text);
        assert_eq!(extras["eth0"], (3, 4, 5, 6));
    }

    #[test]
    fn diskstats_sums_sectors() {
        let text = "   8       0 sda 10 0 100 0 20 0 200 0 0 0 0\n   8       1 sda1 5 0 50 0 10 0 100 0 0 0 0\n";
        let io = parse_diskstats(text);
        assert_eq!(io.read, 150 * 512);
        assert_eq!(io.write, 300 * 512);
    }

    #[test]
    fn uptime_and_os_pretty_parse() {
        assert_eq!(parse_proc_uptime("382100.55 12345.00\n"), 382100);
        assert_eq!(
            read_os_pretty("NAME=\"Ubuntu\"\nPRETTY_NAME=\"Ubuntu 24.04 LTS\"\n"),
            "Ubuntu 24.04 LTS"
        );
        assert_eq!(read_os_pretty("NAME=Arch\n"), "Linux");
    }

    #[test]
    fn cpuinfo_model_reads_known_keys() {
        assert_eq!(
            parse_cpuinfo_model("processor\t: 0\nmodel name\t: AMD Ryzen 7\n"),
            Some("AMD Ryzen 7".into())
        );
        assert_eq!(parse_cpuinfo_model("processor\t: 0\n"), None);
    }

    #[test]
    fn thermal_reading_is_sane() {
        if let Some(temp) = read_thermal_temp() {
            assert!((-50.0..=200.0).contains(&temp));
        }
    }

    #[test]
    fn live_collection_produces_a_usable_snapshot() {
        let cfg = Config::default();
        let mut state = State::default();
        let first = super::super::sample(&mut state, &cfg, 2.0);
        assert!(first.ts > 0);
        assert!(!first.host.is_empty());
        assert!(!first.os.is_empty());
        assert!(first.cpu.cores >= 1);
        assert!(first.memory.total > 0);
        assert!(!first.disks.is_empty());
        let second = super::super::sample(&mut state, &cfg, 2.0);
        assert!(second.cpu.usage_pct >= 0.0 && second.cpu.usage_pct <= 100.0);
    }
}
