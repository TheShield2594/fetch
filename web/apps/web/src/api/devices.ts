/**
 * TVs signed in to this install by pairing: the code a TV shows is confirmed
 * here, and each paired TV can be revoked on its own.
 */

import { request } from "./client";
import type { Device } from "./types";

export const devicesApi = {
  getDevices() {
    return request<{ devices: Device[] }>("/devices");
  },
  confirmPairing(code: string, name?: string) {
    return request<{ deviceName: string }>("/devices/pair/confirm", {
      method: "POST",
      body: JSON.stringify(name ? { code, name } : { code }),
    });
  },
  renameDevice(deviceId: string, name: string) {
    return request<{ device: Device }>(`/devices/${encodeURIComponent(deviceId)}`, {
      method: "PATCH",
      body: JSON.stringify({ name }),
    });
  },
  revokeDevice(deviceId: string) {
    return request<void>(`/devices/${encodeURIComponent(deviceId)}`, { method: "DELETE" });
  },
};
