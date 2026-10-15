use servex_core::config::{Config, Overrides};
use servex_core::{print_connection_info, Agent};
use std::path::PathBuf;
use std::process::ExitCode;

#[cfg(windows)]
mod service;

const USAGE: &str = "\
Usage: servex-agent [options]
       servex-agent install|start|stop|remove   (Windows service)

Options:
  --config <path>    config file (default: servex-agent.json)
  --token <token>    API token (or SERVEX_TOKEN)
  --port <port>      listen port (default 8765)
  --bind <address>   listen address (default 0.0.0.0)
  --tls-cert <path>  TLS certificate (with --tls-key)
  --tls-key <path>   TLS private key (with --tls-cert)
  --init             write a config with a fresh token and exit
  -h, --help         show this help
";

#[derive(Default)]
struct Args {
    config: Option<PathBuf>,
    token: Option<String>,
    port: Option<u16>,
    bind: Option<String>,
    tls_cert: Option<String>,
    tls_key: Option<String>,
    init: bool,
    help: bool,
    command: Option<String>,
}

fn parse_args(raw: &[String]) -> Result<Args, String> {
    let mut args = Args::default();
    let mut i = 0;
    while i < raw.len() {
        let flag = raw[i].clone();
        let value = |i: &mut usize| -> Result<String, String> {
            *i += 1;
            raw.get(*i)
                .cloned()
                .ok_or_else(|| format!("{flag} requires a value"))
        };
        match flag.as_str() {
            "--config" => args.config = Some(PathBuf::from(value(&mut i)?)),
            "--token" => args.token = Some(value(&mut i)?),
            "--port" => {
                args.port = Some(
                    value(&mut i)?
                        .parse()
                        .map_err(|_| "--port must be a number".to_string())?,
                )
            }
            "--bind" => args.bind = Some(value(&mut i)?),
            "--tls-cert" => args.tls_cert = Some(value(&mut i)?),
            "--tls-key" => args.tls_key = Some(value(&mut i)?),
            "--init" => args.init = true,
            "-h" | "--help" => args.help = true,
            other if !other.starts_with('-') && args.command.is_none() => {
                args.command = Some(other.to_string());
            }
            other => return Err(format!("unknown argument: {other}")),
        }
        i += 1;
    }
    Ok(args)
}

fn main() -> ExitCode {
    #[cfg(windows)]
    if let Some(code) = service::try_run_as_service() {
        return ExitCode::from(code);
    }

    let raw: Vec<String> = std::env::args().skip(1).collect();
    let args = match parse_args(&raw) {
        Ok(args) => args,
        Err(message) => {
            eprintln!("error: {message}\n\n{USAGE}");
            return ExitCode::from(2);
        }
    };
    if args.help {
        print!("{USAGE}");
        return ExitCode::SUCCESS;
    }

    #[cfg(windows)]
    if let Some(command) = args.command.as_deref() {
        return ExitCode::from(service::run_command(command, &args));
    }

    run_agent(args)
}

fn run_agent(args: Args) -> ExitCode {
    if args.init {
        let path = args
            .config
            .clone()
            .unwrap_or_else(|| PathBuf::from("servex-agent.json"));
        return match Config::init(&path, args.port, args.bind.clone()) {
            Ok(config) => {
                print_connection_info(&config, true);
                ExitCode::SUCCESS
            }
            Err(message) => {
                eprintln!("error: {message}");
                ExitCode::from(2)
            }
        };
    }

    let overrides = Overrides {
        token: args.token,
        port: args.port,
        bind: args.bind,
        tls_cert: args.tls_cert,
        tls_key: args.tls_key,
    };
    let config = match Config::load(args.config.as_deref(), &overrides) {
        Ok(config) => config,
        Err(message) => {
            eprintln!("error: {message}");
            return ExitCode::from(2);
        }
    };
    if config.token.is_empty() {
        eprintln!("error: a token is required (--token, config, or SERVEX_TOKEN)");
        return ExitCode::from(2);
    }
    serve(config)
}

pub fn serve(config: Config) -> ExitCode {
    let agent = Agent::new(config);
    agent.start_sampler();
    print_connection_info(&agent.config, true);
    match agent.serve() {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("error: {message}");
            ExitCode::from(1)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(raw: &[&str]) -> Result<Args, String> {
        parse_args(&raw.iter().map(|s| s.to_string()).collect::<Vec<_>>())
    }

    #[test]
    fn parses_every_flag() {
        let parsed = args(&[
            "--config",
            "c.json",
            "--token",
            "t",
            "--port",
            "9000",
            "--bind",
            "127.0.0.1",
            "--tls-cert",
            "cert.pem",
            "--tls-key",
            "key.pem",
            "--init",
        ])
        .unwrap();
        assert_eq!(parsed.config, Some(PathBuf::from("c.json")));
        assert_eq!(parsed.token.as_deref(), Some("t"));
        assert_eq!(parsed.port, Some(9000));
        assert_eq!(parsed.bind.as_deref(), Some("127.0.0.1"));
        assert_eq!(parsed.tls_cert.as_deref(), Some("cert.pem"));
        assert_eq!(parsed.tls_key.as_deref(), Some("key.pem"));
        assert!(parsed.init);
    }

    #[test]
    fn first_bare_word_is_the_command() {
        assert_eq!(args(&["install"]).unwrap().command.as_deref(), Some("install"));
        assert_eq!(args(&["--port", "1"]).unwrap().command, None);
    }

    #[test]
    fn rejects_bad_input() {
        assert!(args(&["--port"]).is_err());
        assert!(args(&["--port", "abc"]).is_err());
        assert!(args(&["--nope"]).is_err());
    }
}
