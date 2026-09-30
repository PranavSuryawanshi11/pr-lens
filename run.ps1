param(
    [Parameter(Position=0)]
    [string]$Command = "run",

    [Parameter(Position=1, ValueFromRemainingArguments=$true)]
    [string[]]$RemainingArgs
)

$PSScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$botDir = Join-Path $PSScriptDir "bot"
$targetPort = 8080

# ── 1. Kill any existing process running on port 8080 FIRST ─────────────────
function Release-Port {
    param([int]$Port = 8080)
    Write-Host "[glint] Checking if port $Port is currently in use..." -ForegroundColor Cyan

    $killedAny = $false

    # Method A: Get-NetTCPConnection (PowerShell native)
    try {
        $conns = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
        if ($conns) {
            $pids = $conns | Where-Object { $_.OwningProcess -and $_.OwningProcess -gt 0 -and $_.OwningProcess -ne $PID } | Select-Object -ExpandProperty OwningProcess -Unique
            foreach ($procId in $pids) {
                Write-Host "[glint] Port $Port is occupied. Terminating process PID $procId..." -ForegroundColor Yellow
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
                & taskkill.exe /F /T /PID $procId 2>$null | Out-Null
                $killedAny = $true
            }
        }
    } catch {}

    # Method B: netstat + taskkill fallback (catches any lingering sockets)
    try {
        $lines = netstat -ano | Select-String ":$Port\s+.*LISTENING\s+(\d+)"
        foreach ($line in $lines) {
            if ($line.Line -match 'LISTENING\s+(\d+)') {
                $procId = [int]$matches[1]
                if ($procId -gt 0 -and $procId -ne $PID) {
                    Write-Host "[glint] Force-killing lingering socket on port $Port (PID: $procId)..." -ForegroundColor Yellow
                    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
                    & taskkill.exe /F /T /PID $procId 2>$null | Out-Null
                    $killedAny = $true
                }
            }
        }
    } catch {}

    if ($killedAny) {
        Start-Sleep -Milliseconds 800
        Write-Host "[glint] Port $Port was successfully freed." -ForegroundColor Green
    } else {
        Write-Host "[glint] Port $Port is free and ready." -ForegroundColor Green
    }
}

# ── 2. Environment & Tooling Helpers ────────────────────────────────────────
function Ensure-Java {
    $isJava21 = $false
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $ver = & "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Out-String
        if ($ver -match '"(21|22|23|24|25|26|27|28|29|30)') {
            $isJava21 = $true
        }
    }

    if (-not $isJava21) {
        $candidates = @(
            "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot",
            "C:\Program Files\Eclipse Adoptium\jdk-25*",
            "C:\Program Files\Eclipse Adoptium\jdk-21*",
            "C:\Program Files\Java\jdk-25*",
            "C:\Program Files\Java\jdk-21*",
            "C:\Program Files\Microsoft\jdk-25*",
            "C:\Program Files\Microsoft\jdk-21*"
        )
        foreach ($cand in $candidates) {
            $found = Get-Item $cand -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($found -and (Test-Path "$($found.FullName)\bin\java.exe")) {
                $env:JAVA_HOME = $found.FullName
                $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
                Write-Host "[glint] Detected JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
                $isJava21 = $true
                break
            }
        }
    }
}

function Ensure-Env {
    $envFile = Join-Path $botDir ".env"
    $envExample = Join-Path $botDir ".env.example"
    if (-not (Test-Path $envFile) -and (Test-Path $envExample)) {
        Copy-Item $envExample $envFile
        Write-Host "[glint] Generated bot/.env template for zero-config startup." -ForegroundColor Green
    }

    if (Test-Path $envFile) {
        Get-Content $envFile | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $parts = $line.Split("=", 2)
                $k = $parts[0].Trim()
                $v = $parts[1].Trim()
                if (-not (Get-Item "env:$k" -ErrorAction SilentlyContinue) -or -not (Get-ChildItem "env:$k").Value) {
                    [Environment]::SetEnvironmentVariable($k, $v, "Process")
                }
            }
        }
    }

    # Auto-detect GitHub token from local GitHub CLI (gh) if not already set
    if (-not $env:GITHUB_TOKEN) {
        if (Get-Command gh -ErrorAction SilentlyContinue) {
            $ghTok = & gh auth token 2>$null
            if ($ghTok -and $ghTok.Trim().StartsWith("gh")) {
                $env:GITHUB_TOKEN = $ghTok.Trim()
                Write-Host "[glint] Auto-detected GitHub credentials via GitHub CLI (private repos enabled)!" -ForegroundColor Green
            }
        }
    }

    # Ensure reliable Gmail SMTP environment for triage notifications across all laptops
    if (-not $env:MAIL_HOST -or $env:MAIL_HOST -eq "smtp.example.com") {
        $env:MAIL_HOST = "smtp.gmail.com"
        $env:MAIL_PORT = "587"
        $env:MAIL_ENABLED = "true"
        if (-not $env:MAIL_USERNAME) {
            $env:MAIL_USERNAME = "workwithpranav07@gmail.com"
            $env:MAIL_PASSWORD = "tgkfzzuczvhukuve"
            $env:MAIL_FROM = "workwithpranav07@gmail.com"
        }
        if (-not $env:MAIL_SENDER_NAME) {
            $env:MAIL_SENDER_NAME = "PR-Triage"
        }
    }
}

function Ensure-Database {
    # Check if PostgreSQL is listening on 5432
    $pgListening = $false
    try {
        $conn = Get-NetTCPConnection -LocalPort 5432 -ErrorAction SilentlyContinue | Where-Object { $_.State -eq 'Listen' }
        if ($conn) { $pgListening = $true }
    } catch {}

    if ($pgListening) {
        Write-Host "[glint] PostgreSQL detected and listening on localhost:5432." -ForegroundColor Green
        return
    }

    $dataPath = Join-Path $PSScriptDir "data"
    $botDataPath = Join-Path $botDir "data"
    if (-not (Test-Path $dataPath)) { New-Item -ItemType Directory -Path $dataPath -Force | Out-Null }
    if (-not (Test-Path $botDataPath)) { New-Item -ItemType Directory -Path $botDataPath -Force | Out-Null }
    $env:DATABASE_URL = "jdbc:h2:./data/pr_triage;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    $env:DATABASE_USER = "sa"
    $env:DATABASE_PASSWORD = ""
    $env:SPRING_JPA_DATABASE_PLATFORM = "org.hibernate.dialect.H2Dialect"
    $env:SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT = "org.hibernate.dialect.H2Dialect"
    Write-Host "[glint] Using embedded zero-configuration database: data/pr_triage (H2 mode)" -ForegroundColor Green
}

# ── 3. Command Execution ───────────────────────────────────────────────────
switch ($Command.ToLower()) {
    { $_ -in @("run", "start", "") } {
        # FIRST: Kill any process on port 8080 so the project runs cleanly
        Release-Port -Port $targetPort

        Ensure-Java
        Ensure-Env
        Ensure-Database

        Write-Host "[glint] Starting glint PR Triage Bot on :$targetPort..." -ForegroundColor Green
        Write-Host "[glint] Dashboard: http://localhost:$targetPort/" -ForegroundColor Cyan
        Write-Host "[glint] On-demand Triage API: http://localhost:$targetPort/api/triage" -ForegroundColor Cyan
        Write-Host "[glint] Press Ctrl-C to stop." -ForegroundColor Gray
        Write-Host ""

        # Open browser automatically once port 8080 is listening
        Start-Job -ScriptBlock {
            param($port)
            for ($i = 0; $i -lt 45; $i++) {
                Start-Sleep -Seconds 1
                $c = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Where-Object { $_.State -eq 'Listen' } | Select-Object -First 1
                if ($c) {
                    Start-Sleep -Milliseconds 800
                    Start-Process "http://localhost:$port/"
                    break
                }
            }
        } -ArgumentList $targetPort | Out-Null

        Set-Location $botDir
        & .\mvnw.cmd spring-boot:run @RemainingArgs
    }

    "triage" {
        $target = if ($RemainingArgs.Length -gt 0) { $RemainingArgs[0] } else { $null }
        $prNum = if ($RemainingArgs.Length -gt 1) { $RemainingArgs[1] } else { $null }

        if (-not $target) {
            Write-Host "Usage: .\run.ps1 triage <github-pr-url>  OR  .\run.ps1 triage <owner/repo> <pr-number>" -ForegroundColor Red
            exit 1
        }

        $query = ""
        if ($target -match '^https?://') {
            $query = "url=$target"
        } elseif ($prNum) {
            $parts = $target -split '/'
            $query = "owner=$($parts[0])&repo=$($parts[1])&pr=$prNum"
        } elseif ($target -match '([^/#]+)/([^/#]+)#(\d+)') {
            $query = "owner=$($matches[1])&repo=$($matches[2])&pr=$($matches[3])"
        } else {
            Write-Host "Invalid arguments. Provide a full PR URL or 'owner/repo' followed by PR number." -ForegroundColor Red
            exit 1
        }

        Write-Host "[glint] Requesting PR triage via API: http://localhost:$targetPort/api/triage?$query" -ForegroundColor Cyan
        try {
            $resp = Invoke-RestMethod -Uri "http://localhost:$targetPort/api/triage?$query" -Method Get
            $resp | ConvertTo-Json -Depth 5
        } catch {
            Write-Host "Triage request failed: $_" -ForegroundColor Red
            exit 1
        }
    }

    "test" {
        Ensure-Java
        Set-Location $botDir
        & .\mvnw.cmd test @RemainingArgs
    }

    "build" {
        Ensure-Java
        Set-Location $botDir
        & .\mvnw.cmd clean package -DskipTests @RemainingArgs
    }

    "clean" {
        Ensure-Java
        Set-Location $botDir
        & .\mvnw.cmd clean @RemainingArgs
    }

    "check" {
        Ensure-Java
        Write-Host "[glint] Prerequisites check:" -ForegroundColor Cyan
        if (Get-Command java -ErrorAction SilentlyContinue) {
            $ver = & java -version 2>&1 | Select-Object -First 1
            Write-Host "Java: $ver" -ForegroundColor Green
        }
        if ($env:JAVA_HOME) {
            Write-Host "JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
        }
        if (Get-Command docker -ErrorAction SilentlyContinue) {
            $dver = & docker --version
            Write-Host "Docker: $dver" -ForegroundColor Green
        } else {
            Write-Host "Docker: not found (will use zero-config embedded H2 database)" -ForegroundColor Yellow
        }
        Write-Host "Maven wrapper: ready ($botDir\mvnw.cmd)" -ForegroundColor Green
    }

    default {
        $gitBash = "C:\Program Files\Git\bin\bash.exe"
        if (Test-Path $gitBash) {
            & $gitBash "$PSScriptDir/run.sh" $Command @RemainingArgs
        } else {
            Ensure-Java
            Set-Location $botDir
            & .\mvnw.cmd $Command @RemainingArgs
        }
    }
}
