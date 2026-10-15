use crate::action::builtin_actions;
use crate::config::{token_ok, Config};
use crate::history::{history_points, Ring};
use crate::model::{Snapshot, Summary};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

pub struct RateLimiter {
    per_min: u32,
    hits: Mutex<HashMap<IpAddr, Vec<Instant>>>,
}

impl RateLimiter {
    pub fn new(per_min: u32) -> Self {
        Self {
            per_min,
            hits: Mutex::new(HashMap::new()),
        }
    }

    pub fn allow(&self, ip: IpAddr) -> bool {
        if self.per_min == 0 {
            return true;
        }
        let now = Instant::now();
        let mut hits = self.hits.lock().unwrap();
        if hits.len() > 10_000 {
            hits.retain(|_, window| {
                window.iter().any(|t| now.duration_since(*t) <= Duration::from_secs(60))
            });
        }
        let window = hits.entry(ip).or_default();
        window.retain(|t| now.duration_since(*t) <= Duration::from_secs(60));
        if window.len() >= self.per_min as usize {
            return false;
        }
        window.push(now);
        true
    }
}

pub struct Agent {
    pub config: Config,
    pub ring: Ring,
    latest: Mutex<Option<Snapshot>>,
    limiter: RateLimiter,
    pub actions: HashMap<String, crate::action::Action>,
}

impl Agent {
    pub fn new(config: Config) -> Arc<Self> {
        let registry = builtin_actions();
        let actions = config
            .actions
            .iter()
            .filter_map(|name| {
                registry
                    .get(name.as_str())
                    .map(|action| (name.clone(), Arc::clone(action)))
            })
            .collect();
        Arc::new(Self {
            ring: Ring::new(config.history_size),
            limiter: RateLimiter::new(config.rate_limit_per_min),
            latest: Mutex::new(None),
            actions,
            config,
        })
    }

    pub fn latest(&self) -> Option<Snapshot> {
        self.latest.lock().unwrap().clone()
    }

    pub fn start_sampler(self: &Arc<Self>) -> std::thread::JoinHandle<()> {
        let agent = Arc::clone(self);
        std::thread::spawn(move || {
            let mut state = crate::collect::State::default();
            let interval = agent.config.sample_interval.max(0.1);
            loop {
                let snap = crate::collect::sample(&mut state, &agent.config, interval);
                *agent.latest.lock().unwrap() = Some(snap.clone());
                agent.ring.push(snap);
                std::thread::sleep(Duration::from_secs_f64(interval));
            }
        })
    }

    pub fn bind(self: &Arc<Self>) -> Result<Server, String> {
        let addr = format!("{}:{}", self.config.bind, self.config.port);
        match (&self.config.tls.cert, &self.config.tls.key) {
            (Some(cert), Some(key)) => {
                let ssl = tiny_http::SslConfig {
                    certificate: std::fs::read(cert).map_err(|e| format!("{cert}: {e}"))?,
                    private_key: std::fs::read(key).map_err(|e| format!("{key}: {e}"))?,
                };
                Server::https(addr.as_str(), ssl).map_err(|e| e.to_string())
            }
            _ => Server::http(addr.as_str()).map_err(|e| e.to_string()),
        }
    }

    pub fn serve(self: &Arc<Self>) -> Result<(), String> {
        loop {
            match self.bind() {
                Ok(server) => {
                    if let Err(e) = self.serve_on(server) {
                        eprintln!("servex-agent: serve error: {e}");
                    }
                    eprintln!("servex-agent: accepting connections stopped; restarting in 1s");
                }
                Err(e) => {
                    eprintln!("servex-agent: bind failed: {e}; retrying in 1s");
                }
            }
            std::thread::sleep(Duration::from_secs(1));
        }
    }

    pub fn serve_on(self: &Arc<Self>, server: Server) -> Result<(), String> {
        for request in server.incoming_requests() {
            let agent = Arc::clone(self);
            std::thread::spawn(move || handle(request, &agent));
        }
        Ok(())
    }
}

fn json_response(code: u16, body: String) -> Response<std::io::Cursor<Vec<u8>>> {
    Response::from_string(body)
        .with_status_code(StatusCode(code))
        .with_header(Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).unwrap())
}

fn error(code: u16, message: &str) -> Response<std::io::Cursor<Vec<u8>>> {
    json_response(code, serde_json::json!({ "error": message }).to_string())
}

fn handle(request: Request, agent: &Arc<Agent>) {
    let url = request.url().to_string();
    let (path, query) = match url.split_once('?') {
        Some((path, query)) => (path.to_string(), query.to_string()),
        None => (url, String::new()),
    };

    let ip = request
        .remote_addr()
        .map(|addr| addr.ip())
        .unwrap_or(IpAddr::from([0u8, 0, 0, 0]));
    if !agent.limiter.allow(ip) {
        let _ = request.respond(error(429, "rate limited"));
        return;
    }

    let header = request
        .headers()
        .iter()
        .find(|h| h.field.equiv("Authorization"))
        .map(|h| h.value.as_str().to_string())
        .unwrap_or_default();
    let provided = header.strip_prefix("Bearer ").unwrap_or("");
    if !token_ok(provided, &agent.config.token) {
        let _ = request.respond(error(401, "unauthorized"));
        return;
    }

    let method = request.method().clone();
    match method {
        Method::Get => handle_get(request, agent, &path, &query),
        Method::Post => handle_post(request, agent, &path),
        _ => {
            let _ = request.respond(error(404, "not found"));
        }
    }
}

fn handle_get(request: Request, agent: &Arc<Agent>, path: &str, query: &str) {
    match path {
        "/api/v1/health" => {
            let _ = request.respond(json_response(200, "{\"ok\":true}".into()));
        }
        "/api/v1/summary" => {
            let summary = Summary::from(agent.latest().as_ref());
            let body = serde_json::to_string(&summary).unwrap_or_else(|_| "{}".into());
            let _ = request.respond(json_response(200, body));
        }
        "/api/v1/metrics" => match agent.latest() {
            Some(snap) => {
                let body = serde_json::to_string(&snap).unwrap_or_else(|_| "{}".into());
                let _ = request.respond(json_response(200, body));
            }
            None => {
                let _ = request.respond(error(503, "no sample yet"));
            }
        },
        "/api/v1/history" => {
            let metric = query_param(query, "metric").unwrap_or_else(|| "cpu".into());
            let points = query_param(query, "points")
                .and_then(|v| v.parse().ok())
                .unwrap_or(60);
            let pts = history_points(&agent.ring.snapshot(), &metric, points);
            let body = serde_json::json!({
                "metric": metric,
                "interval_s": agent.config.sample_interval,
                "points": pts,
            });
            let _ = request.respond(json_response(200, body.to_string()));
        }
        _ => {
            let _ = request.respond(error(404, "not found"));
        }
    }
}

fn handle_post(mut request: Request, agent: &Arc<Agent>, path: &str) {
    if path != "/api/v1/action" {
        let _ = request.respond(error(404, "not found"));
        return;
    }
    let length: usize = request
        .headers()
        .iter()
        .find(|h| h.field.equiv("Content-Length"))
        .and_then(|h| h.value.as_str().parse().ok())
        .unwrap_or(0);
    if length > agent.config.max_body_bytes {
        let _ = request.respond(error(413, "body too large"));
        return;
    }
    let mut body = String::new();
    if length > 0 && std::io::Read::read_to_string(request.as_reader(), &mut body).is_err() {
        let _ = request.respond(error(400, "invalid body"));
        return;
    }
    let Ok(payload) = serde_json::from_str::<serde_json::Value>(&body) else {
        let _ = request.respond(error(400, "invalid body"));
        return;
    };
    let Some(action) = payload.get("action").and_then(|a| a.as_str()) else {
        let _ = request.respond(error(400, "invalid body"));
        return;
    };
    let Some(handler) = agent.actions.get(action) else {
        let _ = request.respond(error(403, "action not allowed"));
        return;
    };
    let args = payload.get("args").cloned().unwrap_or(serde_json::Value::Null);
    match handler(&args, agent) {
        Ok(output) => {
            let body = serde_json::json!({ "ok": true, "output": output }).to_string();
            let _ = request.respond(json_response(200, body));
        }
        Err(message) => {
            let _ = request.respond(error(500, &message));
        }
    }
}

fn query_param(query: &str, key: &str) -> Option<String> {
    for pair in query.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            if k == key {
                return Some(v.to_string());
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn http_get(addr: std::net::SocketAddr, path: &str, token: Option<&str>) -> String {
        use std::io::{Read, Write};
        let mut stream = std::net::TcpStream::connect(addr).unwrap();
        let auth = token
            .map(|t| format!("Authorization: Bearer {t}\r\n"))
            .unwrap_or_default();
        let request =
            format!("GET {path} HTTP/1.1\r\nHost: localhost\r\n{auth}Connection: close\r\n\r\n");
        stream.write_all(request.as_bytes()).unwrap();
        let mut out = String::new();
        stream.read_to_string(&mut out).unwrap();
        out
    }

    #[test]
    fn rate_limiter_window() {
        let disabled = RateLimiter::new(0);
        let ip = IpAddr::from([127, 0, 0, 1]);
        for _ in 0..100 {
            assert!(disabled.allow(ip));
        }
        let limited = RateLimiter::new(3);
        assert!(limited.allow(ip));
        assert!(limited.allow(ip));
        assert!(limited.allow(ip));
        assert!(!limited.allow(ip));
        assert!(limited.allow(IpAddr::from([10, 0, 0, 2])));
    }

    #[test]
    fn endpoints_enforce_auth_and_serve_metrics() {
        let config = Config {
            bind: "127.0.0.1".into(),
            port: 0,
            token: "secret".into(),
            sample_interval: 0.05,
            ..Config::default()
        };
        let agent = Agent::new(config);
        agent.start_sampler();
        let server = agent.bind().unwrap();
        let addr = server.server_addr().to_ip().unwrap();
        let serving = Arc::clone(&agent);
        std::thread::spawn(move || {
            let _ = serving.serve_on(server);
        });

        let unauthorized = http_get(addr, "/api/v1/metrics", None);
        assert!(unauthorized.starts_with("HTTP/1.1 401"), "{unauthorized}");
        let unauthorized = http_get(addr, "/api/v1/metrics", Some("wrong"));
        assert!(unauthorized.starts_with("HTTP/1.1 401"), "{unauthorized}");

        let mut body = String::new();
        for _ in 0..40 {
            body = http_get(addr, "/api/v1/metrics", Some("secret"));
            if body.starts_with("HTTP/1.1 200") {
                break;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        assert!(body.starts_with("HTTP/1.1 200"), "{body}");
        assert!(body.contains("\"cpu\""), "{body}");
        assert!(body.contains("\"memory\""), "{body}");

        let health = http_get(addr, "/api/v1/health", Some("secret"));
        assert!(health.contains("{\"ok\":true}"), "{health}");
        let summary = http_get(addr, "/api/v1/summary", Some("secret"));
        assert!(summary.starts_with("HTTP/1.1 200"), "{summary}");
        let history = http_get(addr, "/api/v1/history?metric=cpu&points=5", Some("secret"));
        assert!(history.contains("\"interval_s\""), "{history}");
        let missing = http_get(addr, "/api/v1/nope", Some("secret"));
        assert!(missing.starts_with("HTTP/1.1 404"), "{missing}");
    }

    #[test]
    fn query_params_are_read_in_order() {
        assert_eq!(query_param("metric=cpu&points=5", "metric"), Some("cpu".into()));
        assert_eq!(query_param("metric=cpu&points=5", "points"), Some("5".into()));
        assert_eq!(query_param("metric=cpu", "missing"), None);
        assert_eq!(query_param("", "metric"), None);
    }
}
