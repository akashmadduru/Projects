<#
.SYNOPSIS
    Tears down this project's local Docker footprint (PowerShell port of docker-cleanup.sh).

.DESCRIPTION
    Stops and removes the infra/docker-compose.yml stack (postgres, zookeeper,
    eureka-server, gateway, auth-service, product-service) plus, optionally, the
    named data volumes and the images built from this repo's Dockerfiles.

    SCOPED TO THIS PROJECT ONLY. It never touches containers, images, or volumes
    belonging to other projects on the same machine - it operates strictly on the
    `ecommerce-platform` compose project (see `name:` in docker-compose.yml) plus
    any stray exited containers left over from an interrupted `docker compose
    build` of one of THIS repo's Dockerfiles.

.PARAMETER Volumes
    Also wipe postgres/zookeeper data volumes.

.PARAMETER Images
    Also remove this repo's built images.

.PARAMETER All
    Both -Volumes and -Images.

.PARAMETER ServiceImages
    Remove only service images (eureka, gateway, auth, product, inventory), keep infra images.

.PARAMETER InfraImages
    Remove only infra images (postgres, zookeeper, kafka, redis), keep service images.

.PARAMETER Networks
    Also remove the compose network.

.EXAMPLE
    .\infra\scripts\docker-cleanup.ps1             # stop + remove containers, keep data + images
.EXAMPLE
    .\infra\scripts\docker-cleanup.ps1 -Volumes    # also wipe postgres/zookeeper data
.EXAMPLE
    .\infra\scripts\docker-cleanup.ps1 -Images     # also remove all built images
.EXAMPLE
    .\infra\scripts\docker-cleanup.ps1 -ServiceImages  # only remove service images
.EXAMPLE
    .\infra\scripts\docker-cleanup.ps1 -InfraImages    # only remove infra images
.EXAMPLE
    .\infra\scripts\docker-cleanup.ps1 -Networks   # also remove networks
.EXAMPLE
    .\infra\scripts\docker-cleanup.ps1 -All        # volumes + all images
#>
[CmdletBinding()]
param(
    [switch]$Volumes,
    [switch]$Images,
    [switch]$ServiceImages,
    [switch]$InfraImages,
    [switch]$Networks,
    [switch]$All
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Native {
    # Runs a native command and stops the script on a non-zero exit (bash `set -e`).
    param([Parameter(Mandatory)][string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "'$Exe $($Arguments -join ' ')' failed with exit code $LASTEXITCODE"
    }
}

if ($All) { $Volumes = $true; $Images = $true }

# Repo root: two levels up from this script (infra/scripts -> repo root).
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $repoRoot
try {
    $composeFile = 'infra/docker-compose.yml'
    $projectName = 'ecommerce-platform'

    Write-Host "== Stopping and removing the $projectName compose stack =="
    if ($Volumes) {
        Write-Host '   (including named volumes: postgres-data, zookeeper-data, zookeeper-datalog)'
        Invoke-Native docker @('compose', '-f', $composeFile, 'down', '-v', '--remove-orphans')
    } else {
        Invoke-Native docker @('compose', '-f', $composeFile, 'down', '--remove-orphans')
    }

    Write-Host ''
    Write-Host "== Removing stray containers left over from interrupted builds of this repo =="
    # `docker compose build` runs each Dockerfile RUN step in its own throwaway
    # container; if a build is killed mid-step, that container is orphaned instead
    # of being cleaned up, so `down` above may miss it.
    $strayContainers = @(docker ps -aq --filter "label=com.docker.compose.project=$projectName" --filter 'status=exited' |
        Where-Object { $_ })
    if ($LASTEXITCODE -ne 0) { throw "docker ps failed with exit code $LASTEXITCODE" }
    if ($strayContainers.Count -gt 0) {
        Invoke-Native docker (@('rm', '-f') + $strayContainers)
    } else {
        Write-Host '   none found'
    }

    if ($Images -or $ServiceImages -or $InfraImages) {
        Write-Host ''

        if ($Images) {
            # Remove all built images
            Write-Host "== Removing all images built from this repo's Dockerfiles =="
            $servicePattern = "^$projectName-(eureka-server|gateway|auth-service|product-service|inventory-service):"
            $infraPattern = "^(postgres|zookeeper|apache/kafka|redis):"
            $allImages = @(docker images --format '{{.Repository}}:{{.Tag}}' |
                Where-Object { $_ -match $servicePattern -or $_ -match $infraPattern })
            if ($LASTEXITCODE -ne 0) { throw "docker images failed with exit code $LASTEXITCODE" }
            if ($allImages.Count -gt 0) {
                $allImages | ForEach-Object { Write-Host $_ }
                Invoke-Native docker (@('rmi', '-f') + $allImages)
            } else {
                Write-Host '   none found'
            }
        }

        if ($ServiceImages) {
            Write-Host "== Removing service images (eureka, gateway, auth, product, inventory) =="
            $pattern = "^$projectName-(eureka-server|gateway|auth-service|product-service|inventory-service):"
            $builtImages = @(docker images --format '{{.Repository}}:{{.Tag}}' |
                Where-Object { $_ -match $pattern })
            if ($LASTEXITCODE -ne 0) { throw "docker images failed with exit code $LASTEXITCODE" }
            if ($builtImages.Count -gt 0) {
                $builtImages | ForEach-Object { Write-Host $_ }
                Invoke-Native docker (@('rmi', '-f') + $builtImages)
            } else {
                Write-Host '   none found'
            }
        }

        if ($InfraImages) {
            Write-Host "== Removing infra images (postgres, zookeeper, kafka, redis) =="
            $infraPatterns = @('postgres:', 'zookeeper:', 'apache/kafka:', 'redis:')
            $infraImages = @(docker images --format '{{.Repository}}:{{.Tag}}' |
                Where-Object { $image = $_; $infraPatterns | Where-Object { $image -match [regex]::Escape($_) } | Select-Object -First 1 })
            if ($LASTEXITCODE -ne 0) { throw "docker images failed with exit code $LASTEXITCODE" }
            if ($infraImages.Count -gt 0) {
                $infraImages | ForEach-Object { Write-Host $_ }
                Invoke-Native docker (@('rmi', '-f') + $infraImages)
            } else {
                Write-Host '   none found'
            }
        }
    }

    Write-Host ''
    Write-Host "== Dangling images and build cache from this repo's builds =="
    Invoke-Native docker @('image', 'prune', '-f') | Out-Null
    # Best effort, like `|| true` in the bash version.
    docker builder prune -f --filter "label=com.docker.compose.project=$projectName" *> $null
    $global:LASTEXITCODE = 0

    if ($Networks) {
        Write-Host ''
        Write-Host "== Removing networks created by this project =="
        $networks = @(docker network ls --format '{{.Name}}' --filter "label=com.docker.compose.project=$projectName" |
            Where-Object { $_ })
        if ($LASTEXITCODE -ne 0) { throw "docker network ls failed with exit code $LASTEXITCODE" }
        if ($networks.Count -gt 0) {
            $networks | ForEach-Object { Write-Host $_ }
            Invoke-Native docker (@('network', 'rm') + $networks)
        } else {
            Write-Host '   none found'
        }
    }

    Write-Host ''
    Write-Host 'Done.'
    if (-not $Volumes) {
        Write-Host 'Data volumes were kept. Re-run with -Volumes to wipe postgres/zookeeper data too.'
    }
    if (-not $Images -and -not $ServiceImages -and -not $InfraImages) {
        Write-Host "Built images were kept (faster next 'up'). Re-run with -Images to remove them, -ServiceImages for app images only, or -InfraImages for backing-service images only."
    }
    if (-not $Networks) {
        Write-Host 'Networks were kept. Re-run with -Networks to remove them too.'
    }
}
finally {
    Pop-Location
}
