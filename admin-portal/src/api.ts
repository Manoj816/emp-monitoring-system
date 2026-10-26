// Client for the admin API (see backend AdminController). Dev auth is the
// X-Admin-Key header; Cognito JWTs replace it before production.

export interface Employee {
  id: string;
  employeeCode: string;
  fullName: string;
  email: string;
  department: string | null;
  status: string;
  hasReferenceImage: boolean;
}

export interface AdminCheckRow {
  checkId: string;
  status: string;
  mismatchFlag: boolean;
  similarityScore: number | null;
  checkType: string;
  employeeId: string;
  employeeCode: string | null;
  employeeName: string | null;
  deviceId: string;
  hasEvidence: boolean;
  errorDetail: string | null;
  requestedAt: string;
  completedAt: string | null;
  reviewResolution: string | null;
}

export type ReviewResolution =
  | "CONFIRMED_EMPLOYEE"
  | "CONFIRMED_MISMATCH"
  | "TECHNICAL_ISSUE"
  | "INCONCLUSIVE";

export interface DeviceSummary {
  id: string;
  employeeId: string;
  employeeName: string | null;
  platform: string;
  hostname: string | null;
  status: string;
  agentVersion: string | null;
  registeredAt: string | null;
  lastSeenAt: string | null;
}

const KEY_STORAGE = "empverify.adminKey";

export function getAdminKey(): string {
  try {
    return localStorage.getItem(KEY_STORAGE) ?? "dev-admin-key";
  } catch {
    return "dev-admin-key";
  }
}

export function setAdminKey(key: string): void {
  try {
    localStorage.setItem(KEY_STORAGE, key);
  } catch {
    // storage unavailable: key just lives for this page load
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { "X-Admin-Key": getAdminKey(), ...(init?.headers ?? {}) },
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body?.error) message = body.error;
    } catch {
      // non-JSON error body
    }
    throw new Error(message);
  }
  return (await response.json()) as T;
}

export const api = {
  listEmployees: () => request<Employee[]>("/api/v1/admin/employees"),

  createEmployee: (input: { employeeCode: string; fullName: string; email: string; department?: string }) =>
    request<Employee>("/api/v1/admin/employees", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
    }),

  uploadReferenceImage: (employeeId: string, image: Blob) => {
    const form = new FormData();
    form.append("image", image, "reference.jpg");
    return request<Employee>(`/api/v1/admin/employees/${employeeId}/reference-image`, {
      method: "POST",
      body: form,
    });
  },

  issueEnrollmentToken: (employeeId: string) =>
    request<{ enrollmentToken: string }>(`/api/v1/admin/employees/${employeeId}/enrollment-tokens`, {
      method: "POST",
    }),

  listDevices: () => request<DeviceSummary[]>("/api/v1/admin/devices"),

  revokeDevice: (deviceId: string) =>
    fetch(`/api/v1/admin/devices/${deviceId}/revoke`, {
      method: "POST",
      headers: { "X-Admin-Key": getAdminKey() },
    }).then((r) => {
      if (!r.ok) throw new Error(`revoke failed: ${r.status}`);
    }),

  offboardEmployee: (employeeId: string) =>
    request<{ employeeId: string; status: string; devicesRevoked: number }>(
      `/api/v1/admin/employees/${employeeId}/offboard`,
      { method: "POST" },
    ),

  listChecks: (limit = 100) => request<AdminCheckRow[]>(`/api/v1/admin/checks?limit=${limit}`),

  reviewCheck: (checkId: string, resolution: ReviewResolution, notes: string) =>
    request<AdminCheckRow>(`/api/v1/admin/checks/${checkId}/review`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ resolution, notes }),
    }),

  loadEvidence: async (checkId: string): Promise<string> => {
    const response = await fetch(`/api/v1/admin/checks/${checkId}/evidence`, {
      headers: { "X-Admin-Key": getAdminKey() },
    });
    if (!response.ok) throw new Error(`evidence: ${response.status}`);
    return URL.createObjectURL(await response.blob());
  },
};
