pub mod action;
pub mod collect;
pub mod config;
pub mod history;
pub mod model;
pub mod server;

pub use config::Config;
pub use history::Ring;
pub use model::Snapshot;
pub use server::Agent;

use std::net::{IpAddr, ToSocketAddrs};

/// Round to one decimal place, matching the Python agent's `round(x, 1)`.
pub fn round1(v: f64) -> f64 {
    (v * 10.0).round() / 10.0
}

/// Every address the agent can be reached at, loopback first.
pub fn lan_addresses(port: u16) -> Vec<String> {
    let mut urls = vec![format!("http://127.0.0.1:{port}")];
    let host = collect::hostname();
    if let Ok(addrs) = (host.as_str(), 0u16).to_socket_addrs() {
        for addr in addrs {
            if let IpAddr::V4(ip) = addr.ip() {
                if !ip.is_loopback() {
                    let url = format!("http://{ip}:{port}");
                    if !urls.contains(&url) {
                        urls.push(url);
                    }
                }
            }
        }
    }
    urls
}

pub fn print_connection_info(config: &Config, show_token: bool) {
    eprintln!("servex-agent listening on {}:{}", config.bind, config.port);
    for url in lan_addresses(config.port) {
        eprintln!("  {url}");
    }
    if show_token {
        eprintln!("  Token: {}", config.token);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round1_matches_python_rounding() {
        assert_eq!(round1(37.25), 37.3);
        assert_eq!(round1(0.04), 0.0);
        assert_eq!(round1(99.99), 100.0);
    }

    #[test]
    fn lan_addresses_starts_with_loopback() {
        let urls = lan_addresses(8765);
        assert_eq!(urls[0], "http://127.0.0.1:8765");
        assert!(urls.iter().all(|u| u.contains(":8765")));
    }
}
