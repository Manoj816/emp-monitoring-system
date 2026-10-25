# Dev helper for the admin API (local backend, dev admin key).
# Usage (via npm): npm run employees | npm run token | npm run checks
#   npm run token -- E-0002   -> token for that employee code (default: first employee)
param(
    [Parameter(Mandatory = $true)][ValidateSet("employees", "token", "checks")]
    [string]$Command,
    [string]$EmployeeCode
)

$ErrorActionPreference = "Stop"
$base = "http://localhost:8082"
$headers = @{ "X-Admin-Key" = "dev-admin-key" }

function Get-Employees {
    Invoke-RestMethod "$base/api/v1/admin/employees" -Headers $headers
}

switch ($Command) {
    "employees" {
        Get-Employees | Format-Table employeeCode, fullName, email, status, hasReferenceImage -AutoSize
    }
    "token" {
        $emps = Get-Employees
        $emp = if ($EmployeeCode) { $emps | Where-Object employeeCode -eq $EmployeeCode } else { $emps | Select-Object -First 1 }
        if (-not $emp) { Write-Error "No employee found$(if ($EmployeeCode) { " with code $EmployeeCode" })."; exit 1 }
        $tok = Invoke-RestMethod -Method Post "$base/api/v1/admin/employees/$($emp.id)/enrollment-tokens" -Headers $headers
        Write-Output "Enrollment token for $($emp.fullName) ($($emp.employeeCode)) - single use, 24h:"
        Write-Output ""
        Write-Output "  $($tok.enrollmentToken)"
        Write-Output ""
        Write-Output "Paste it into the agent's registration screen."
    }
    "checks" {
        Invoke-RestMethod "$base/api/v1/admin/checks" -Headers $headers |
            Format-Table @{n="check";e={$_.checkId.Substring(0,8)}}, status, similarityScore, mismatchFlag, completedAt -AutoSize
    }
}
