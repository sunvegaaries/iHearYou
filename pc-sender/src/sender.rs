// pc-sender/src/sender.rs
// UDP sender — packet format: [seq:4][ts:4][opus:N] (big-endian)

use std::net::UdpSocket;
use anyhow::{Context, Result};

pub struct UdpSender {
    socket: UdpSocket,
    target: String,
}

impl UdpSender {
    pub fn new(ip: &str, port: u16) -> Result<Self> {
        let socket = UdpSocket::bind("0.0.0.0:0").context("bind UDP socket")?;
        let target = format!("{ip}:{port}");
        socket.connect(&target).context("connect UDP")?;
        log::info!("[udp] Socket ready → {target}");
        Ok(Self { socket, target })
    }

    /// Send one audio packet. Stack-allocated buffer, no heap alloc.
    pub fn send(&self, seq: u32, timestamp: u32, opus: &[u8]) {
        let mut buf = [0u8; 8 + 400];
        let len = opus.len().min(400);

        // Header: big-endian seq + timestamp
        buf[0] = (seq >> 24) as u8;
        buf[1] = (seq >> 16) as u8;
        buf[2] = (seq >>  8) as u8;
        buf[3] =  seq        as u8;
        buf[4] = (timestamp >> 24) as u8;
        buf[5] = (timestamp >> 16) as u8;
        buf[6] = (timestamp >>  8) as u8;
        buf[7] =  timestamp        as u8;

        buf[8..8 + len].copy_from_slice(&opus[..len]);

        if let Err(e) = self.socket.send(&buf[..8 + len]) {
            log::warn!("[udp] send error: {e}");
        }
    }
}
