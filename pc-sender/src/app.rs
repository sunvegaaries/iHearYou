// pc-sender/src/app.rs
// egui application — UI state + session start/stop

use std::sync::{Arc, atomic::{AtomicBool, AtomicU32, Ordering}};
use egui::{Color32, RichText};
use crate::audio::AudioSession;

// Shared stats updated by background threads, read by UI
pub struct Stats {
    pub packets_sent: AtomicU32,
    pub last_opus_bytes: AtomicU32,
    pub loss_plp: AtomicU32,
}

impl Default for Stats {
    fn default() -> Self {
        Self {
            packets_sent:    AtomicU32::new(0),
            last_opus_bytes: AtomicU32::new(0),
            loss_plp:        AtomicU32::new(5),
        }
    }
}

pub struct SenderApp {
    // Form fields
    target_ip:   String,
    target_port: String,

    // Session state
    running:     bool,
    status:      String,
    stop_flag:   Arc<AtomicBool>,
    stats:       Arc<Stats>,

    // Active session handle (keeps threads alive)
    session:     Option<AudioSession>,
}

impl Default for SenderApp {
    fn default() -> Self {
        Self {
            target_ip:   String::from("192.168.1.100"),
            target_port: String::from("5005"),
            running:     false,
            status:      String::from("Ready"),
            stop_flag:   Arc::new(AtomicBool::new(false)),
            stats:       Arc::new(Stats::default()),
            session:     None,
        }
    }
}

impl eframe::App for SenderApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Repaint every 500ms to refresh stats
        ctx.request_repaint_after(std::time::Duration::from_millis(500));

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(12.0);

            ui.heading("AudioShare v2");
            ui.label(RichText::new("Streams PC audio → Android over Wi-Fi")
                .color(Color32::GRAY).small());

            ui.add_space(16.0);
            ui.separator();
            ui.add_space(12.0);

            // --- Connection fields ---
            egui::Grid::new("fields")
                .num_columns(2)
                .spacing([12.0, 8.0])
                .show(ui, |ui| {
                    ui.label("Android IP:");
                    ui.add_enabled(
                        !self.running,
                        egui::TextEdit::singleline(&mut self.target_ip)
                            .desired_width(160.0)
                            .hint_text("192.168.1.100"),
                    );
                    ui.end_row();

                    ui.label("Port:");
                    ui.add_enabled(
                        !self.running,
                        egui::TextEdit::singleline(&mut self.target_port)
                            .desired_width(80.0)
                            .hint_text("5005"),
                    );
                    ui.end_row();
                });

            ui.add_space(12.0);

            // --- Connect / Disconnect buttons ---
            ui.horizontal(|ui| {
                if !self.running {
                    if ui.button("▶  Connect").clicked() {
                        self.start_session();
                    }
                } else {
                    if ui.button("■  Disconnect").clicked() {
                        self.stop_session();
                    }
                }
            });

            ui.add_space(12.0);
            ui.separator();
            ui.add_space(8.0);

            // --- Status ---
            let status_color = if self.running { Color32::GREEN } else { Color32::GRAY };
            ui.label(RichText::new(&self.status).color(status_color));

            // --- Stats (only while running) ---
            if self.running {
                ui.add_space(8.0);
                let pkts = self.stats.packets_sent.load(Ordering::Relaxed);
                let bytes = self.stats.last_opus_bytes.load(Ordering::Relaxed);
                let plp   = self.stats.loss_plp.load(Ordering::Relaxed);

                egui::Grid::new("stats")
                    .num_columns(2)
                    .spacing([8.0, 4.0])
                    .show(ui, |ui| {
                        ui.label(RichText::new("Packets sent:").small());
                        ui.label(RichText::new(pkts.to_string()).small().strong());
                        ui.end_row();

                        ui.label(RichText::new("Last frame:").small());
                        ui.label(RichText::new(format!("{bytes} bytes")).small());
                        ui.end_row();

                        ui.label(RichText::new("FEC PLP:").small());
                        ui.label(RichText::new(format!("{plp}%")).small());
                        ui.end_row();
                    });
            }
        });
    }
}

impl SenderApp {
    fn start_session(&mut self) {
        let port: u16 = match self.target_port.trim().parse() {
            Ok(p) => p,
            Err(_) => { self.status = "Invalid port".into(); return; }
        };

        let ip = self.target_ip.trim().to_string();
        if ip.is_empty() { self.status = "Enter IP address".into(); return; }

        self.stop_flag = Arc::new(AtomicBool::new(false));
        self.stats     = Arc::new(Stats::default());

        match AudioSession::start(ip.clone(), port, self.stop_flag.clone(), self.stats.clone()) {
            Ok(session) => {
                self.session = Some(session);
                self.running = true;
                self.status  = format!("Streaming → {ip}:{port}");
            }
            Err(e) => {
                self.status = format!("Error: {e}");
            }
        }
    }

    fn stop_session(&mut self) {
        self.stop_flag.store(true, Ordering::Relaxed);
        self.session = None; // drops session, threads exit
        self.running = false;
        self.status  = "Disconnected".into();
    }
}
