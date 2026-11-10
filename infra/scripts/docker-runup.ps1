<#
.SYNOPSIS
    Brings up this project's complete Docker stack for local development.

.DESCRIPTION
    Starts all services from infra/docker-compose.yml in the correct dependency order:
    1. Backing services: postgres, zookeeper, kafka, redis
    2. Platform services: eureka-server, auth-service, gateway, product-service, inventory-service

    Waits for health checks and verifies that all services are running before exiting.
    If services are already running, use -Rebuild to recreate images from latest Dockerfiles.

.PARAMETER Rebuild
    Rebuild all images before starting (docker compose up --build).

.PARAMETER NoWait
    Start services but do not wait for health checks to pass.

.EXAMPLE
    .\infra\scripts\docker-runup.ps1             # start all services and wait for health
.EXAMPLE
    .\infra\scripts\docker-runup.ps1 -Rebuild    # rebuild images, then start
.EXAMPLE
    .\infra\scripts\docker-runup.ps1 -NoWait     # start services without waiting for health
#>
[CmdletBinding()]
param(
    [switch]$Rebuild,
    [switch]$NoWait
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Native {
    param([Parameter(Mandatory)][string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "'$Exe $($Arguments -join ' ')' failed with exit code $LASTEXITCODE"
    }
}

function Wait-ServiceHealthy {
    param(
        [Parameter(Mandatory)][string]$ServiceName,
        [int]$MaxWaitSeconds = 120
    )

    Write-Host "Waiting for $ServiceName to be healthy..."
    $startTime = Get-Date
    $containerName = "ecommerce-$($ServiceName.Replace('-', ''))"

    while ((Get-Date) - $startTime -lt [TimeSpan]::FromSeconds($MaxWaitSeconds)) {
        $health = docker inspect $containerName --format='{{.State.Health.Status}}' 2>$null

        if ($health -eq 'healthy') {
            Write-Host "✓ $ServiceName is healthy"
            return $true
        }

        Start-Sleep -Seconds 2
    }

    Write-Host "✗ $ServiceName did not become healthy within $MaxWaitSeconds seconds"
    return $false
}

# Repo root: two levels up from this script
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $repoRoot
try {
    $composeFile = 'infra/docker-compose.yml'
    $projectName = 'ecommerce-platform'

    Write-Host "== Starting $projectName Docker stack =="
    Write-Host ''

    $upArgs = @('compose', '-f', $composeFile, 'up', '-d')
    if ($Rebuild) {
        Write-Host "Rebuilding images..."
        $upArgs += '--build'
    }

    Invoke-Native docker $upArgs

    if (-not $NoWait) {
        Write-Host ''
        Write-Host "== Waiting for services to become healthy =="
        Write-Host ''

        $services = @(
            'postgres',
            'zookeeper',
            'kafka',
            'redis',
            'eureka-server',
            'auth-service',
            'gateway',
            'product-service',
            'inventory-service'
        )

        $allHealthy = $true
        foreach ($service in $services) {
            if (-not (Wait-ServiceHealthy $service)) {
                $allHealthy = $false
            }
        }

        Write-Host ''
        if ($allHealthy) {
            Write-Host '✓ All services are healthy and ready!'
            Write-Host ''
            Write-Host 'Service URLs:'
            Write-Host '  Gateway:        http://localhost:8080'
            Write-Host '  Auth Service:   http://127.0.0.1:8081'
            Write-Host '  Product Service: http://127.0.0.1:8082'
            Write-Host '  Inventory Service: http://127.0.0.1:8083'
            Write-Host '  Eureka Registry: http://127.0.0.1:8761'
            Write-Host '  Postgres:       localhost:5432 (postgres / postgres)'
            Write-Host '  Redis:          localhost:6379'
            Write-Host '  Zookeeper:      localhost:2181'
        } else {
            Write-Host '✗ Some services failed to become healthy. Check logs with: docker compose logs'
            exit 1
        }
    } else {
        Write-Host 'Services started. Skipping health checks.'
        Write-Host ''
        Write-Host 'To check service status, run: docker compose -f infra/docker-compose.yml ps'
        Write-Host 'To view logs, run: docker compose -f infra/docker-compose.yml logs'
    }
}
finally {
    Pop-Location
}
