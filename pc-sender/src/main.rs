// pc-sender/src/main.rs
// AudioShare v2 — PC Sender (Rust + egui + cpal + opus)

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")] // hide console in release

mod app;
mod audio;
mod encoder;
mod sender;

use eframe::NativeOptions;

fn main() -> anyhow::Result<()> {
    env_logger::init();

    let options = NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("AudioShare Sender")
            .with_inner_size([360.0, 280.0])
            .with_resizable(false),
        ..Default::default()
    };

    eframe::run_native(
        "AudioShare Sender",
        options,
        Box::new(|_cc| Ok(Box::new(app::SenderApp::default()))),
    )
    .map_err(|e| anyhow::anyhow!("eframe error: {e}"))
}
