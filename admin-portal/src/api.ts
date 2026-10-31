// Client for the admin API (see backend AdminController). Every call carries the
// signed-in admin's Cognito ID token as a Bearer JWT (see auth.ts).
import { idToken } from "./auth";

/** Departments an employee can belong to (fixed list for now; also the assign popup's filter). */
export const DEPARTMENTS = ["Engineering", "Sales", "Human Resources", "Finance"] as const;

export interface Employee {
  id: string;
  employeeCode: string;
  fullName: string;
  email: string;
  department: string | null;
  status: string;
  /** An administrator has uploaded the ID/photo reference. */
  hasReferenceImage: boolean;
  /** The employee's first live scan matched it and is now the second reference. */
  hasLiveEnrollment: boolean;
}

export interface AdminCheckRow {
  checkId: string;
  status: string;
  mismatchFlag: boolean;
  similarityScore: number | null;
  /** Live-person confidence (0-100) from the face scan; null for photo-capture checks. */
  livenessConfidence: number | null;
  checkType: string;
  employeeId: string;
  employeeCode: string | null;
  employeeName: string | null;
  /** Null once the system the check ran on was deleted; the check stays with the employee. */
  deviceId: string | null;
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
  /** Null for a device-first registration that no admin has assigned yet. */
  employeeId: string | null;
  employeeName: string | null;
  platform: string;
  hostname: string | null;
  status: string;
  agentVersion: string | null;
  registeredAt: string | null;
  lastSeenAt: string | null;
  /** When the enrollment scan on this system matched; null until the system's first check. */
  enrolledAt: string | null;
}

async function authHeaders(): Promise<Record<string, string>> {
  const token = await idToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { ...(await authHeaders()), ...(init?.headers ?? {}) },
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

  /** Creates the employee together with the mandatory ID/photo reference (one multipart request). */
  createEmployee: (
    input: { employeeCode: string; fullName: string; email: string; department?: string },
    image: Blob,
  ) => {
    const form = new FormData();
    form.append("employeeCode", input.employeeCode);
    form.append("fullName", input.fullName);
    form.append("email", input.email);
    if (input.department) form.append("department", input.department);
    form.append("image", image, "reference.jpg");
    return request<Employee>("/api/v1/admin/employees", { method: "POST", body: form });
  },

  /** Corrects name/code/email/department. The photo and status have their own actions; nothing re-enrolls. */
  updateEmployee: (
    employeeId: string,
    input: { employeeCode: string; fullName: string; email: string; department: string },
  ) =>
    request<Employee>(`/api/v1/admin/employees/${employeeId}`, {
      method: "PUT",
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

  /** Test env only: wipes all demo data. The server refuses unless DEMO_RESET_ENABLED. */
  resetDemoData: () =>
    request<{ employees: number; devices: number; checks: number; imagesDeleted: number }>(
      "/api/v1/admin/demo/reset",
      { method: "POST" },
    ),

  revokeDevice: async (deviceId: string) => {
    const r = await fetch(`/api/v1/admin/devices/${deviceId}/revoke`, {
      method: "POST",
      headers: await authHeaders(),
    });
    if (!r.ok) throw new Error(`revoke failed: ${r.status}`);
  },

  /** Removes a system row (stale duplicate, retired machine); its checks stay with the employee. */
  deleteDevice: async (deviceId: string) => {
    const r = await fetch(`/api/v1/admin/devices/${deviceId}`, {
      method: "DELETE",
      headers: await authHeaders(),
    });
    if (!r.ok) throw new Error(`delete failed: ${r.status}`);
  },

  /** Links a registered (unassigned) system to an employee and activates it. */
  assignDevice: (deviceId: string, employeeId: string) =>
    request<DeviceSummary>(`/api/v1/admin/devices/${deviceId}/assign`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ employeeId }),
    }),

  /** Asks the employee's systems for the enrollment scan again (the current live scan is retired). */
  requestReEnrollment: (employeeId: string) =>
    request<{ employeeId: string; systemsReset: number }>(`/api/v1/admin/employees/${employeeId}/re-enroll`, {
      method: "POST",
    }),

  /** Marks the employee OFFBOARDED and deregisters their systems (back to Unassigned) in the same step. */
  offboardEmployee: (employeeId: string) =>
    request<{ employeeId: string; status: string; devicesDeregistered: number }>(
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
      headers: await authHeaders(),
    });
    if (!response.ok) throw new Error(`evidence: ${response.status}`);
    return URL.createObjectURL(await response.blob());
  },

  /** The uploaded ID/photo reference, for side-by-side review. */
  loadReferenceImage: async (employeeId: string): Promise<string> => {
    const response = await fetch(`/api/v1/admin/employees/${employeeId}/reference-image`, {
      headers: await authHeaders(),
    });
    if (!response.ok) throw new Error(`reference image: ${response.status}`);
    return URL.createObjectURL(await response.blob());
  },

  /** The frame from the employee's first matching live scan (the live-enrollment reference). */
  loadEnrolledImage: async (employeeId: string): Promise<string> => {
    const response = await fetch(`/api/v1/admin/employees/${employeeId}/enrolled-image`, {
      headers: await authHeaders(),
    });
    if (!response.ok) throw new Error(`enrolled image: ${response.status}`);
    return URL.createObjectURL(await response.blob());
  },
};
