use crate::Args;
use servex_core::collect::run_cmd;
use servex_core::config::{default_config_path, Config};
use servex_core::Agent;
use std::ffi::OsString;
use std::path::PathBuf;
use std::sync::mpsc;
use std::time::Duration;
use windows_service::service::{
    ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::{define_windows_service, service_dispatcher};

const SERVICE_NAME: &str = "ServexAgent";
const DISPLAY_NAME: &str = "Servex Agent";
const FIREWALL_RULE: &str = "Servex Agent";

define_windows_service!(ffi_service_main, service_main);

pub fn try_run_as_service() -> Option<u8> {
    service_dispatcher::start(SERVICE_NAME, ffi_service_main).ok().map(|_| 0)
}

fn service_main(_arguments: Vec<OsString>) {
    let _ = run_service();
}

fn status(state: ServiceState) -> ServiceStatus {
    ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: state,
        controls_accepted: ServiceControlAccept::STOP,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    }
}

fn run_service() -> windows_service::Result<()> {
    let (stop_tx, stop_rx) = mpsc::channel::<()>();
    let handler = move |control| match control {
        ServiceControl::Stop => {
            let _ = stop_tx.send(());
            ServiceControlHandlerResult::NoError
        }
        ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
        _ => ServiceControlHandlerResult::NotImplemented,
    };
    let status_handle = service_control_handler::register(SERVICE_NAME, handler)?;
    status_handle.set_service_status(status(ServiceState::Running))?;

    let config =
        Config::load(Some(&default_config_path()), &Default::default()).unwrap_or_default();
    if config.token.is_empty() {
        status_handle.set_service_status(status(ServiceState::Stopped))?;
        return Ok(());
    }

    let agent = Agent::new(config);
    agent.start_sampler();
    let serving = std::sync::Arc::clone(&agent);
    std::thread::spawn(move || {
        let _ = serving.serve();
    });

    let _ = stop_rx.recv();
    status_handle.set_service_status(status(ServiceState::Stopped))?;
    std::process::exit(0);
}

pub fn run_command(command: &str, args: &Args) -> u8 {
    let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from("ServexAgent.exe"));
    let port = args.port.unwrap_or(8765);
    match command {
        "install" => {
            let path = args.config.clone().unwrap_or_else(default_config_path);
            if let Err(message) = Config::init(&path, args.port, args.bind.clone()) {
                eprintln!("error: {message}");
                return 2;
            }
            let bin_path = format!("\"{}\" --config \"{}\"", exe.display(), path.display());
            if !sc(&[
                "create",
                SERVICE_NAME,
                "binPath=",
                &bin_path,
                "start=",
                "auto",
                "DisplayName=",
                DISPLAY_NAME,
            ]) {
                eprintln!("error: could not create the service (run this from an elevated prompt)");
                return 1;
            }
            firewall("add", port);
            if !sc(&["start", SERVICE_NAME]) {
                eprintln!("warning: the service was installed but did not start");
            }
            println!("installed {SERVICE_NAME}; config: {}", path.display());
            0
        }
        "start" => report("start", sc(&["start", SERVICE_NAME])),
        "stop" => report("stop", sc(&["stop", SERVICE_NAME])),
        "remove" => {
            sc(&["stop", SERVICE_NAME]);
            let removed = sc(&["delete", SERVICE_NAME]);
            firewall("delete", port);
            report("remove", removed)
        }
        other => {
            eprintln!("error: unknown command: {other}");
            2
        }
    }
}

fn report(action: &str, ok: bool) -> u8 {
    if ok {
        println!("{action}: ok");
        0
    } else {
        eprintln!("error: could not {action} the service (run this from an elevated prompt)");
        1
    }
}

fn sc(args: &[&str]) -> bool {
    run_cmd("sc.exe", args, 30).is_some()
}

fn firewall(action: &str, port: u16) {
    let rule = format!("name={FIREWALL_RULE}");
    let port = format!("localport={port}");
    let ok = run_cmd(
        "netsh",
        &[
            "advfirewall",
            "firewall",
            action,
            "rule",
            &rule,
            "dir=in",
            "action=allow",
            "protocol=TCP",
            &port,
        ],
        30,
    )
    .is_some();
    if !ok && action == "add" {
        eprintln!("warning: could not add the firewall rule");
    }
}
