#!/usr/bin/env bash
#
# run.sh — One-command runner and triage CLI for glint PR-Lens Bot.
#
# Usage:
#   ./run.sh                     # Start the entire bot (auto-configures DB, Java, .env)
#   ./run.sh triage <pr-url>     # Triage ANY pull request on ANY GitHub account
#   ./run.sh triage <owner/repo> <pr-number>
#   ./run.sh test [args...]      # Run tests
#   ./run.sh build               # Build runnable jar
#   ./run.sh db-up / db-down     # Manage PostgreSQL container
#   ./run.sh check               # Verify environment & tooling
#   ./run.sh help                # Show help
#
set -euo pipefail

# ── Paths ──────────────────────────────────────────────────────────────────
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOT_DIR="${ROOT_DIR}/bot"
DATA_DIR="${ROOT_DIR}/data"
MVN="${BOT_DIR}/mvnw"

# ── Pretty Output ──────────────────────────────────────────────────────────
info()    { printf '\033[1;34m[glint]\033[0m %s\n' "$*"; }
success() { printf '\033[1;32m[glint]\033[0m %s\n' "$*"; }
warn()    { printf '\033[1;33m[glint]\033[0m %s\n' "$*" >&2; }
error()   { printf '\033[1;31m[glint]\033[0m %s\n' "$*" >&2; }
die()     { error "$*"; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# ── Java Detection ─────────────────────────────────────────────────────────
ensure_java() {
    # Check if current JAVA_HOME has Java 21+
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        if "${JAVA_HOME}/bin/java" -version 2>&1 | grep -Eq '"(21|22|23|24|25|26|27|28|29|30)'; then
            return 0
        fi
    fi

    # Check if java in PATH has Java 21+
    if have java; then
        if java -version 2>&1 | grep -Eq '"(21|22|23|24|25|26|27|28|29|30)'; then
            local jbin
            jbin="$(command -v java)"
            local jhome
            jhome="$(cd "$(dirname "$jbin")/.." 2>/dev/null && pwd)"
            if [ -d "$jhome" ]; then
                export JAVA_HOME="$jhome"
            fi
            return 0
        fi
    fi

    # Search common JDK 21+ paths on Windows, Linux, and macOS
    local candidate
    for candidate in \
        "C:/Program Files/Eclipse Adoptium/jdk-25"* \
        "C:/Program Files/Eclipse Adoptium/jdk-21"* \
        "C:/Program Files/Java/jdk-25"* \
        "C:/Program Files/Java/jdk-21"* \
        "C:/Program Files/Microsoft/jdk-25"* \
        "C:/Program Files/Microsoft/jdk-21"* \
        "/usr/lib/jvm/java-25"* \
        "/usr/lib/jvm/java-21"* \
        "/Library/Java/JavaVirtualMachines/"*25* \
        "/Library/Java/JavaVirtualMachines/"*21*; do
        if [ -d "$candidate" ] && [ -x "$candidate/bin/java" ]; then
            info "Detected JDK 21+ at: ${candidate}"
            export JAVA_HOME="${candidate}"
            return 0
        fi
    done

    warn "Could not locate Java 21+ automatically."
    return 1
}

# ── Environment Setup ──────────────────────────────────────────────────────
ensure_env() {
    local example="${BOT_DIR}/.env.example"
    local target="${BOT_DIR}/.env"

    if [ ! -f "${target}" ]; then
        if [ -f "${example}" ]; then
            cp "${example}" "${target}"
            info "Generated bot/.env template for zero-config startup."
        fi
    fi

    # Auto-detect token from GitHub CLI if not already set
    if [ -z "${GITHUB_TOKEN:-}" ] && have gh; then
        local gh_tok
        gh_tok="$(gh auth token 2>/dev/null || true)"
        if [ -n "${gh_tok}" ]; then
            export GITHUB_TOKEN="${gh_tok}"
            info "Auto-detected GitHub credentials via GitHub CLI (private repos enabled)!"
        fi
    fi
}

# ── Database Setup ─────────────────────────────────────────────────────────
is_port_listening() {
    local port="$1"
    if have nc; then
        nc -z localhost "$port" >/dev/null 2>&1 && return 0
    fi
    (echo > "/dev/tcp/localhost/${port}") >/dev/null 2>&1 && return 0
    if have powershell.exe; then
        powershell.exe -NoProfile -Command "\$ProgressPreference = 'SilentlyContinue'; Test-NetConnection -ComputerName 'localhost' -Port $port -InformationLevel Quiet" 2>/dev/null | grep -qi "true" && return 0
    fi
    return 1
}

is_docker_functional() {
    have docker && docker ps >/dev/null 2>&1
}

ensure_database() {
    # 1. If already listening on 5432, use it
    if is_port_listening 5432; then
        info "PostgreSQL detected and listening on localhost:5432."
        return 0
    fi

    # 2. Try starting Docker container if Docker is functional
    if is_docker_functional; then
        local container="${DB_CONTAINER:-glint-postgres}"
        local image="${DB_IMAGE:-postgres:16-alpine}"
        if docker ps -a --format '{{.Names}}' | grep -qx "${container}"; then
            info "Starting existing PostgreSQL container '${container}'..."
            docker start "${container}" >/dev/null
        else
            info "Starting PostgreSQL container '${container}' (${image})..."
            docker run -d \
                --name "${container}" \
                -e POSTGRES_DB="pr_triage" \
                -e POSTGRES_USER="postgres" \
                -e POSTGRES_PASSWORD="postgres" \
                -p "5432:5432" \
                "${image}" >/dev/null
        fi

        # Wait briefly for ready
        for _ in $(seq 1 15); do
            if is_port_listening 5432; then
                success "PostgreSQL container is ready on localhost:5432."
                return 0
            fi
            sleep 1
        done
    fi

    # 3. Fallback to embedded zero-config H2 database
    mkdir -p "${DATA_DIR}" "${BOT_DIR}/data"
    export DATABASE_URL="jdbc:h2:./data/pr_triage;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    export DATABASE_USER="sa"
    export DATABASE_PASSWORD=""
    info "Using embedded zero-configuration database: data/pr_triage (H2 mode)"
}

# ── Maven Runner ───────────────────────────────────────────────────────────
run_mvn() {
    ensure_java || true
    if [ ! -x "${MVN}" ]; then
        chmod +x "${MVN}" 2>/dev/null || true
    fi

    (cd "${BOT_DIR}" && "${MVN}" "$@")
}

# ── Commands ───────────────────────────────────────────────────────────────

cmd_run() {
    local target_port="${PORT:-8080}"
    info "Checking and releasing port ${target_port} if occupied..."
    if have powershell.exe; then
        powershell.exe -NoProfile -Command "
            \$ProgressPreference = 'SilentlyContinue'
            \$conns = Get-NetTCPConnection -LocalPort ${target_port} -ErrorAction SilentlyContinue
            if (\$conns) {
                \$conns | Where-Object { \$_.OwningProcess -gt 0 } | ForEach-Object {
                    Stop-Process -Id \$_.OwningProcess -Force -ErrorAction SilentlyContinue
                }
            }
            netstat -ano | Select-String \":${target_port}\s+.*LISTENING\s+(\d+)\" | ForEach-Object {
                if (\$_ -match 'LISTENING\s+(\d+)') {
                    taskkill.exe /F /T /PID \$matches[1] 2>`$null | Out-Null
                }
            }
        " 2>/dev/null || true
    fi

    ensure_java || true
    ensure_env
    ensure_database

    info "Starting glint PR-Lens Bot on :${PORT:-8080}..."
    info "Dashboard: http://localhost:${PORT:-8080}/"
    info "On-demand Triage API: http://localhost:${PORT:-8080}/api/triage"
    info "Press Ctrl-C to stop."
    echo ""

    # Automatically launch web dashboard in browser when server is ready
    (
        for _ in $(seq 1 45); do
            sleep 1
            if is_port_listening "${target_port}"; then
                sleep 1
                if have powershell.exe; then
                    powershell.exe -NoProfile -Command "Start-Process 'http://localhost:${target_port}/'" 2>/dev/null || true
                elif have xdg-open; then
                    xdg-open "http://localhost:${target_port}/" 2>/dev/null || true
                elif have open; then
                    open "http://localhost:${target_port}/" 2>/dev/null || true
                fi
                break
            fi
        done
    ) &

    run_mvn spring-boot:run
}

cmd_triage() {
    local target="${1:-}"
    local pr_number="${2:-}"

    if [ -z "${target}" ]; then
        error "Usage: ./run.sh triage <github-pr-url>  OR  ./run.sh triage <owner/repo> <pr-number>"
        echo "Example: ./run.sh triage https://github.com/facebook/react/pull/28000"
        echo "Example: ./run.sh triage octocat/Hello-World 12"
        exit 1
    fi

    # Check if local bot is running
    local bot_port="${PORT:-8080}"
    if ! is_port_listening "${bot_port}"; then
        warn "Glint bot is not currently running on port ${bot_port}."
        info "Please start the bot in another terminal with './run.sh', or wait while we check..."
    fi

    local query=""
    if [[ "${target}" =~ ^https?:// ]]; then
        query="url=${target}"
    elif [ -n "${pr_number}" ]; then
        local owner="${target%/*}"
        local repo="${target#*/}"
        query="owner=${owner}&repo=${repo}&pr=${pr_number}"
    else
        # Maybe format owner/repo#123
        if [[ "${target}" == *"#"* ]]; then
            local full_repo="${target%#*}"
            local pnum="${target#*#}"
            local owner="${full_repo%/*}"
            local repo="${full_repo#*/}"
            query="owner=${owner}&repo=${repo}&pr=${pnum}"
        else
            error "Invalid arguments. Provide a full PR URL or 'owner/repo' followed by PR number."
            exit 1
        fi
    fi

    info "Requesting PR triage via API: http://localhost:${bot_port}/api/triage?${query}"
    if have curl; then
        local resp
        resp="$(curl -s -w "\n%{http_code}" "http://localhost:${bot_port}/api/triage?${query}")"
        local code
        code="$(echo "${resp}" | tail -n1)"
        local body
        body="$(echo "${resp}" | sed '$d')"

        if [ "${code}" -eq 200 ]; then
            success "Triage Response:"
            echo "${body}"
        else
            error "Triage request failed with HTTP ${code}:"
            echo "${body}"
            exit 1
        fi
    else
        die "curl is required to run triage CLI."
    fi
}

cmd_test() {
    ensure_java || true
    info "Running tests..."
    run_mvn test "$@"
}

cmd_build() {
    ensure_java || true
    info "Building runnable JAR..."
    run_mvn clean package -DskipTests
    success "Build complete! JAR location: bot/target/"
}

cmd_clean() {
    ensure_java || true
    info "Cleaning build files..."
    run_mvn clean
    success "Clean complete."
}

cmd_check() {
    ensure_java || true
    info "Prerequisites check:"
    if have java; then
        info "Java: $(java -version 2>&1 | head -1)"
    else
        warn "java not found in PATH"
    fi
    if [ -n "${JAVA_HOME:-}" ]; then
        info "JAVA_HOME: ${JAVA_HOME}"
    fi
    if have docker; then
        info "Docker: $(docker --version)"
    else
        info "Docker: not found (will use zero-config embedded database)"
    fi
    if [ -x "${MVN}" ]; then
        info "Maven wrapper: ready (${MVN})"
    fi
}

cmd_db_up() {
    is_docker_functional || die "Docker is required for db-up"
    local container="${DB_CONTAINER:-glint-postgres}"
    local image="${DB_IMAGE:-postgres:16-alpine}"

    if docker ps -a --format '{{.Names}}' | grep -qx "${container}"; then
        info "Starting container '${container}'..."
        docker start "${container}" >/dev/null
    else
        info "Creating PostgreSQL container '${container}'..."
        docker run -d \
            --name "${container}" \
            -e POSTGRES_DB="pr_triage" \
            -e POSTGRES_USER="postgres" \
            -e POSTGRES_PASSWORD="postgres" \
            -p "5432:5432" \
            "${image}" >/dev/null
    fi
    success "PostgreSQL container '${container}' is up."
}

cmd_db_down() {
    is_docker_functional || die "Docker is required for db-down"
    local container="${DB_CONTAINER:-glint-postgres}"
    info "Stopping container '${container}'..."
    docker rm -f "${container}" >/dev/null 2>&1 || true
    success "Container '${container}' removed."
}

cmd_help() {
    cat << 'EOF'
glint PR-Lens Bot — One-Command Runner

Usage:
  ./run.sh [command] [options]

Commands:
  (no command)                 Start the entire bot with one command (auto-detects DB, Java, .env)
  run                          Alias for default run
  triage <url | owner/repo #>  Triage ANY pull request on ANY account via GitHub API
  test [args...]               Run test suite
  build                        Package application jar (tests skipped)
  check                        Check tooling and environment
  db-up                        Start PostgreSQL container
  db-down                      Stop PostgreSQL container
  clean                        Clean build artifacts
  help                         Show this help message

Authentication Modes:
  1. Zero-Config / Tokenless:
     Fetches PR diffs and metadata for ANY public repository on GitHub without any auth or config.
  2. Personal Access Token (PAT):
     Set GITHUB_TOKEN=ghp_... in bot/.env or export GITHUB_TOKEN.
     Allows triaging private repos, increases rate limits, and enables review comments on any repo you access.
  3. GitHub App:
     Set GITHUB_APP_ID and certs/github-app.pem in bot/.env for full GitHub App webhooks and installations.

Examples:
  ./run.sh
  ./run.sh triage https://github.com/facebook/react/pull/28000
  ./run.sh triage octocat/Hello-World 12
  ./run.sh test
EOF
}

# ── Main ───────────────────────────────────────────────────────────────────
main() {
    local cmd="${1:-run}"
    if [ $# -gt 0 ]; then
        shift
    fi

    case "${cmd}" in
        run|start)       cmd_run "$@" ;;
        triage)          cmd_triage "$@" ;;
        test)            cmd_test "$@" ;;
        build)           cmd_build "$@" ;;
        clean)           cmd_clean "$@" ;;
        check)           cmd_check "$@" ;;
        db-up)           cmd_db_up "$@" ;;
        db-down)         cmd_db_down "$@" ;;
        help|-h|--help)  cmd_help ;;
        *)
            error "Unknown command: ${cmd}"
            echo ""
            cmd_help
            exit 1
            ;;
    esac
}

main "$@"
