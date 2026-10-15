#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use eframe::egui;
use servex_core::config::{default_config_path, Config};
use servex_core::{lan_addresses, Agent};
use std::path::PathBuf;
use std::sync::Arc;

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([470.0, 580.0])
            .with_min_inner_size([470.0, 580.0]),
        ..Default::default()
    };
    eframe::run_native(
        "Servex Agent",
        options,
        Box::new(|_cc| Ok(Box::new(App::new()))),
    )
}

struct App {
    config_path: PathBuf,
    agent: Option<Arc<Agent>>,
    error: Option<String>,
    autostart: bool,
    firewall_rule: bool,
    copied_at: Option<std::time::Instant>,
}

impl App {
    fn new() -> Self {
        let config_path = pick_config_path();
        let mut app = Self {
            config_path,
            agent: None,
            error: None,
            autostart: autostart_enabled(),
            firewall_rule: firewall_rule_exists(),
            copied_at: None,
        };
        app.start();
        app
    }

    fn start(&mut self) {
        if !self.config_path.exists() {
            if let Err(message) = Config::init(&self.config_path, None, None) {
                self.error = Some(message);
                return;
            }
        }
        match Config::load(Some(&self.config_path), &Default::default()) {
            Ok(config) if !config.token.is_empty() => {
                let agent = Agent::new(config.clone());
                agent.start_sampler();
                let serving = Arc::clone(&agent);
                std::thread::spawn(move || {
                    let _ = serving.serve();
                });
                self.agent = Some(agent);
                self.error = None;
            }
            Ok(_) => {
                self.error = Some("the config has no token (run servex-agent --init)".into());
            }
            Err(message) => self.error = Some(message),
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if ctx.input(|i| i.viewport().close_requested()) {
            ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
            ctx.send_viewport_cmd(egui::ViewportCommand::Minimized(true));
        }

        let agent = self.agent.clone();
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("Servex Agent");
            ui.add_space(4.0);

            match (&agent, &self.error) {
                (Some(agent), _) => {
                    ui.horizontal(|ui| {
                        ui.colored_label(egui::Color32::from_rgb(80, 200, 120), "●");
                        ui.label(format!("Running on port {}", agent.config.port));
                    });
                    ui.add_space(10.0);
                    ui.label("Add this server in the Servex app:");
                    ui.add_space(4.0);
                    for url in lan_addresses(agent.config.port) {
                        ui.monospace(url);
                    }
                    ui.add_space(10.0);
                    ui.label("Token:");
                    ui.horizontal(|ui| {
                        ui.monospace(&agent.config.token);
                        if ui.button("Copy").clicked() {
                            ctx.copy_text(agent.config.token.clone());
                            self.copied_at = Some(std::time::Instant::now());
                        }
                    });
                    if self
                        .copied_at
                        .map(|t| t.elapsed().as_secs() < 2)
                        .unwrap_or(false)
                    {
                        ui.label("copied to the clipboard");
                    }
                }
                (None, Some(error)) => {
                    ui.colored_label(egui::Color32::from_rgb(220, 90, 90), "Not running");
                    ui.add_space(4.0);
                    ui.label(error);
                    ui.add_space(8.0);
                    if ui.button("Retry").clicked() {
                        self.start();
                    }
                }
                (None, None) => {
                    ui.label("Starting...");
                }
            }

            ui.add_space(16.0);
            ui.separator();
            ui.add_space(8.0);

            let mut autostart = self.autostart;
            if ui.checkbox(&mut autostart, "Start with Windows").changed() {
                match set_autostart(autostart) {
                    Ok(()) => self.autostart = autostart,
                    Err(message) => self.error = Some(message),
                }
            }

            ui.add_space(6.0);
            ui.horizontal(|ui| {
                if ui.button("Allow in firewall").clicked() {
                    allow_firewall(agent.as_ref().map(|a| a.config.port).unwrap_or(8765));
                }
                if ui.button("Recheck").clicked() {
                    self.firewall_rule = firewall_rule_exists();
                }
                ui.label(if self.firewall_rule {
                    "firewall rule present"
                } else {
                    "firewall rule not found"
                });
            });

            ui.add_space(16.0);
            ui.separator();
            ui.add_space(8.0);
            ui.label("Config file:");
            ui.monospace(self.config_path.display().to_string());
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                if ui.button("Quit").clicked() {
                    std::process::exit(0);
                }
                ui.label("closing this window keeps the agent running");
            });
        });
    }
}

fn pick_config_path() -> PathBuf {
    let default = default_config_path();
    if default.exists() {
        return default;
    }
    let local = PathBuf::from("servex-agent.json");
    if local.exists() {
        return local;
    }
    default
}

#[cfg(windows)]
fn autostart_enabled() -> bool {
    use winreg::enums::{HKEY_CURRENT_USER, KEY_READ};
    use winreg::RegKey;
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey_with_flags(r"Software\Microsoft\Windows\CurrentVersion\Run", KEY_READ)
        .and_then(|key| key.get_value::<String, _>("ServexAgent"))
        .is_ok()
}

#[cfg(not(windows))]
fn autostart_enabled() -> bool {
    false
}

#[cfg(windows)]
fn set_autostart(enabled: bool) -> Result<(), String> {
    use winreg::enums::{HKEY_CURRENT_USER, KEY_SET_VALUE};
    use winreg::RegKey;
    let key = RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey_with_flags(r"Software\Microsoft\Windows\CurrentVersion\Run", KEY_SET_VALUE)
        .map_err(|e| e.to_string())?;
    if enabled {
        let exe = std::env::current_exe().map_err(|e| e.to_string())?;
        key.set_value("ServexAgent", &format!("\"{}\"", exe.display()))
            .map_err(|e| e.to_string())
    } else {
        let _ = key.delete_value("ServexAgent");
        Ok(())
    }
}

#[cfg(not(windows))]
fn set_autostart(_enabled: bool) -> Result<(), String> {
    Err("autostart is only supported on Windows".into())
}

#[cfg(windows)]
fn firewall_rule_exists() -> bool {
    servex_core::collect::run_cmd(
        "netsh",
        &[
            "advfirewall",
            "firewall",
            "show",
            "rule",
            "name=Servex Agent",
        ],
        10,
    )
    .is_some()
}

#[cfg(not(windows))]
fn firewall_rule_exists() -> bool {
    false
}

#[cfg(windows)]
fn allow_firewall(port: u16) {
    let netsh_args = format!(
        "advfirewall firewall add rule name=\"Servex Agent\" dir=in action=allow protocol=TCP localport={port}"
    );
    let _ = std::process::Command::new("powershell")
        .args([
            "-NoProfile",
            "-Command",
            &format!("Start-Process -Verb RunAs -FilePath netsh -ArgumentList '{netsh_args}'"),
        ])
        .spawn();
}

#[cfg(not(windows))]
fn allow_firewall(_port: u16) {}
