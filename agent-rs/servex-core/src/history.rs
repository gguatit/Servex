use crate::model::Snapshot;
use crate::round1;
use serde::Serialize;
use std::collections::VecDeque;
use std::sync::Mutex;

pub struct Ring {
    size: usize,
    data: Mutex<VecDeque<Snapshot>>,
}

impl Ring {
    pub fn new(size: usize) -> Self {
        Self {
            size: size.max(1),
            data: Mutex::new(VecDeque::new()),
        }
    }

    pub fn push(&self, snap: Snapshot) {
        let mut data = self.data.lock().unwrap();
        data.push_back(snap);
        while data.len() > self.size {
            data.pop_front();
        }
    }

    pub fn snapshot(&self) -> Vec<Snapshot> {
        self.data.lock().unwrap().iter().cloned().collect()
    }

    pub fn len(&self) -> usize {
        self.data.lock().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Point {
    pub t: i64,
    pub v: f64,
}

pub fn history_points(snapshots: &[Snapshot], metric: &str, points: usize) -> Vec<Point> {
    let value = |s: &Snapshot| -> f64 {
        match metric {
            "cpu" => s.cpu.usage_pct,
            "memory" => s.memory.pct,
            "disk" => s.disks.iter().map(|d| d.pct).fold(0.0_f64, f64::max),
            "network" => s.network.iter().map(|n| n.rx_bps as f64).sum(),
            _ => 0.0,
        }
    };
    if !matches!(metric, "cpu" | "memory" | "disk" | "network") {
        return Vec::new();
    }
    let start = snapshots.len().saturating_sub(points);
    snapshots[start..]
        .iter()
        .map(|s| Point {
            t: s.ts,
            v: round1(value(s)),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Disk, NetEntry};

    fn snap(ts: i64, cpu: f64, mem: f64, disk: f64, rx: u64) -> Snapshot {
        let mut s = Snapshot {
            ts,
            ..Default::default()
        };
        s.cpu.usage_pct = cpu;
        s.memory.pct = mem;
        s.disks.push(Disk {
            mount: "/".into(),
            pct: disk,
            ..Default::default()
        });
        s.network.push(NetEntry {
            iface: "eth0".into(),
            rx_bps: rx,
            ..Default::default()
        });
        s
    }

    #[test]
    fn ring_keeps_the_last_items() {
        let ring = Ring::new(3);
        for i in 0..5 {
            ring.push(snap(i, 0.0, 0.0, 0.0, 0));
        }
        assert_eq!(ring.len(), 3);
        let ts: Vec<i64> = ring.snapshot().iter().map(|s| s.ts).collect();
        assert_eq!(ts, vec![2, 3, 4]);
    }

    #[test]
    fn history_points_are_rounded_and_clamped() {
        let snaps: Vec<Snapshot> = (0..10).map(|i| snap(i, 1.25, 2.0, 3.0, 100)).collect();
        let cpu = history_points(&snaps, "cpu", 4);
        assert_eq!(cpu.len(), 4);
        assert_eq!(cpu[0].t, 6);
        assert_eq!(cpu[0].v, 1.3);
        let many = history_points(&snaps, "cpu", 999);
        assert_eq!(many.len(), 10);
        let none = history_points(&snaps, "cpu", 0);
        assert!(none.is_empty());
    }

    #[test]
    fn history_points_cover_every_metric() {
        let snaps = vec![snap(1, 10.0, 20.0, 30.0, 1024)];
        assert_eq!(history_points(&snaps, "memory", 5)[0].v, 20.0);
        assert_eq!(history_points(&snaps, "disk", 5)[0].v, 30.0);
        assert_eq!(history_points(&snaps, "network", 5)[0].v, 1024.0);
        assert!(history_points(&snaps, "bogus", 5).is_empty());
    }

    #[test]
    fn disk_metric_takes_the_fullest_mount() {
        let mut s = snap(1, 0.0, 0.0, 30.0, 0);
        s.disks.push(Disk {
            mount: "/data".into(),
            pct: 91.5,
            ..Default::default()
        });
        assert_eq!(history_points(&[s], "disk", 1)[0].v, 91.5);
    }
}
