use super::{cpu_usage, docker, gpu, now_epoch, top_processes, CpuCounters, State};
use crate::model::*;
use crate::round1;
use crate::Config;
use windows_sys::Win32::Foundation::{CloseHandle, FILETIME, HANDLE};
use windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
use windows_sys::Win32::System::ProcessStatus::{
    EnumProcesses, GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS,
};
use windows_sys::Win32::System::SystemInformation::{
    GetTickCount64, GlobalMemoryStatusEx, MEMORYSTATUSEX,
};
use windows_sys::Win32::System::Threading::{
    GetProcessTimes, GetSystemTimes, OpenProcess, QueryFullProcessImageNameW,
    PROCESS_QUERY_LIMITED_INFORMATION,
};

pub fn collect(snap: &mut Snapshot, st: &mut State, _cfg: &Config, _dt: f64) {
    let release = release();
    snap.os = format!("Windows {release}");
    snap.kernel = release;
    snap.arch = std::env::var("PROCESSOR_ARCHITECTURE")
        .unwrap_or_else(|_| std::env::consts::ARCH.to_string());

    let cur_cpu = cpu_times();
    let cores = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    snap.cpu = Cpu {
        usage_pct: match (&st.prev_cpu, &cur_cpu) {
            (Some(prev), Some(cur)) => cpu_usage(prev, cur),
            _ => 0.0,
        },
        cores,
        threads: cores,
        model: std::env::var("PROCESSOR_IDENTIFIER").ok(),
        ..Default::default()
    };

    snap.memory = memory();

    match boot_time() {
        Some(boot) => {
            snap.boot_time = Some(boot);
            snap.uptime_s = (now_epoch() - boot).max(0) as u64;
        }
        None => snap.uptime_s = 0,
    }

    snap.disks = disks();
    snap.network = Vec::new();

    let procs = windows_processes(snap.memory.total, snap.uptime_s, cores);
    if !procs.is_empty() {
        snap.processes = Some(top_processes(procs));
    }
    if let Some(services) = services() {
        snap.services = Some(services);
    }
    if let Some(gpus) = gpu() {
        snap.gpu = Some(gpus);
    }
    if let Some(docker) = docker() {
        snap.docker = Some(docker);
    }

    st.prev_cpu = cur_cpu;
}

fn filetime(ft: FILETIME) -> u64 {
    ((ft.dwHighDateTime as u64) << 32) | ft.dwLowDateTime as u64
}

fn release() -> String {
    let build: u32 = registry_string(
        r"SOFTWARE\Microsoft\Windows NT\CurrentVersion",
        "CurrentBuildNumber",
    )
    .and_then(|s| s.parse().ok())
    .unwrap_or(0);
    if build >= 22000 {
        "11".into()
    } else {
        "10".into()
    }
}

fn registry_string(path: &str, name: &str) -> Option<String> {
    use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ};
    use winreg::RegKey;
    let key = RegKey::predef(HKEY_LOCAL_MACHINE)
        .open_subkey_with_flags(path, KEY_READ)
        .ok()?;
    key.get_value(name).ok()
}

fn memory() -> Memory {
    let mut status: MEMORYSTATUSEX = unsafe { std::mem::zeroed() };
    status.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
    if unsafe { GlobalMemoryStatusEx(&mut status) } == 0 {
        return Memory::default();
    }
    let total = status.ullTotalPhys;
    let available = status.ullAvailPhys;
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

fn cpu_times() -> Option<CpuCounters> {
    let (mut idle, mut kernel, mut user): (FILETIME, FILETIME, FILETIME) =
        unsafe { std::mem::zeroed() };
    if unsafe { GetSystemTimes(&mut idle, &mut kernel, &mut user) } == 0 {
        return None;
    }
    Some(CpuCounters {
        idle: filetime(idle),
        total: filetime(kernel) + filetime(user),
    })
}

fn boot_time() -> Option<i64> {
    let ticks = unsafe { GetTickCount64() };
    if ticks == 0 {
        return None;
    }
    Some(now_epoch() - (ticks / 1000) as i64)
}

fn disks() -> Vec<Disk> {
    let mut out = Vec::new();
    for letter in b'A'..=b'Z' {
        let root = format!("{}:\\", letter as char);
        let wide: Vec<u16> = root.encode_utf16().chain(std::iter::once(0)).collect();
        let (mut free_to_caller, mut total, mut total_free) = (0u64, 0u64, 0u64);
        let ok = unsafe {
            GetDiskFreeSpaceExW(
                wide.as_ptr(),
                &mut free_to_caller,
                &mut total,
                &mut total_free,
            )
        };
        if ok == 0 || total == 0 {
            continue;
        }
        let used = total.saturating_sub(total_free);
        out.push(Disk {
            mount: root,
            total,
            used,
            pct: round1(used as f64 / total as f64 * 100.0),
        });
    }
    out
}

fn windows_processes(mem_total: u64, uptime_s: u64, cores: usize) -> Vec<Proc> {
    if uptime_s == 0 {
        return Vec::new();
    }
    let mut pids = vec![0u32; 4096];
    let mut needed = 0u32;
    if unsafe { EnumProcesses(pids.as_mut_ptr(), (pids.len() * 4) as u32, &mut needed) } == 0 {
        return Vec::new();
    }
    pids.truncate(needed as usize / 4);

    let mut out = Vec::new();
    for pid in pids {
        if pid == 0 {
            continue;
        }
        let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
        if handle.is_null() {
            continue;
        }
        let (mut creation, mut exit, mut kernel, mut user): (FILETIME, FILETIME, FILETIME, FILETIME) =
            unsafe { std::mem::zeroed() };
        let times_ok =
            unsafe { GetProcessTimes(handle, &mut creation, &mut exit, &mut kernel, &mut user) };
        let mut counters: PROCESS_MEMORY_COUNTERS = unsafe { std::mem::zeroed() };
        counters.cb = std::mem::size_of::<PROCESS_MEMORY_COUNTERS>() as u32;
        let mem_ok = unsafe { GetProcessMemoryInfo(handle, &mut counters, counters.cb) };
        let name = process_name(handle);
        unsafe { CloseHandle(handle) };
        if times_ok == 0 || mem_ok == 0 {
            continue;
        }
        let cpu_seconds = (filetime(kernel) + filetime(user)) as f64 / 10_000_000.0;
        out.push(Proc {
            pid,
            name,
            cpu_pct: round1(cpu_seconds / uptime_s as f64 * 100.0 / cores.max(1) as f64),
            mem_pct: if mem_total > 0 {
                round1(counters.WorkingSetSize as f64 / mem_total as f64 * 100.0)
            } else {
                0.0
            },
        });
    }
    out
}

fn process_name(handle: HANDLE) -> String {
    let mut buf = [0u16; 260];
    let mut size = buf.len() as u32;
    if unsafe { QueryFullProcessImageNameW(handle, 0, buf.as_mut_ptr(), &mut size) } == 0 {
        return String::new();
    }
    let path = String::from_utf16_lossy(&buf[..size as usize]);
    path.rsplit(['\\', '/'])
        .next()
        .unwrap_or(path.as_str())
        .to_string()
}

fn services() -> Option<Services> {
    use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ};
    use winreg::RegKey;
    let key = RegKey::predef(HKEY_LOCAL_MACHINE)
        .open_subkey_with_flags(r"SYSTEM\CurrentControlSet\Services", KEY_READ)
        .ok()?;
    let mut total = 0usize;
    for name in key.enum_keys().flatten() {
        if let Ok(sub) = key.open_subkey(&name) {
            let start: u32 = sub.get_value("Start").unwrap_or(0);
            if start == 2 {
                total += 1;
            }
        }
    }
    Some(Services {
        failed: Vec::new(),
        total: Some(total),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn live_collection_produces_a_usable_snapshot() {
        let cfg = Config::default();
        let mut state = State::default();
        let first = super::super::sample(&mut state, &cfg, 2.0);
        assert!(first.ts > 0);
        assert!(!first.host.is_empty());
        assert!(first.os.starts_with("Windows"));
        assert!(first.cpu.cores >= 1);
        assert!(first.memory.total > 0);
        assert!(!first.disks.is_empty());
        let second = super::super::sample(&mut state, &cfg, 2.0);
        assert!((0.0..=100.0).contains(&second.cpu.usage_pct));
    }

    #[test]
    fn registry_release_is_known() {
        assert!(matches!(release().as_str(), "10" | "11"));
    }
}
