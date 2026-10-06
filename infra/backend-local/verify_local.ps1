param([switch]$SkipFailureChecks)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
$compose = Join-Path $PSScriptRoot "compose.yaml"

docker compose -f $compose up -d --build --wait
python (Join-Path $PSScriptRoot "verify_contract.py")

try {
    $env:CATALOG_UPSTREAM = "node:8080"
    docker compose -f $compose up -d --force-recreate --wait nginx
    $rollback = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18080/v1/shop/catalog
    if ($rollback.Headers["X-Instance-Id"]) { throw "rollback still points at Spring" }

    $env:CATALOG_UPSTREAM = "spring_catalog"
    docker compose -f $compose up -d --force-recreate --wait nginx
    Write-Host "PASS: two-instance routing and Node rollback checks"

    if (-not $SkipFailureChecks) {
        docker compose -f $compose stop spring spring-b
        $catalogStatus = try { (Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18080/v1/shop/catalog).StatusCode } catch {
            $_.Exception.Response.StatusCode.value__
        }
        if ($catalogStatus -ne 502) { throw "expected catalog 502 while Spring was stopped, got $catalogStatus" }
        if ((Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18080/v1/health).StatusCode -ne 200) {
            throw "Node health failed while Spring was stopped"
        }
        docker compose -f $compose up -d --wait spring spring-b

        docker compose -f $compose stop postgres
        Start-Sleep -Seconds 3
        if ((Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18082/actuator/health/liveness).StatusCode -ne 200) {
            throw "liveness followed the database"
        }
        $readinessStatus = try { (Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18082/actuator/health/readiness).StatusCode } catch {
            $_.Exception.Response.StatusCode.value__
        }
        if ($readinessStatus -ne 503) { throw "expected readiness 503 while PostgreSQL was stopped, got $readinessStatus" }
        Write-Host "PASS: Spring-down and DB-down checks"
    }
}
finally {
    Remove-Item Env:CATALOG_UPSTREAM -ErrorAction SilentlyContinue
    docker compose -f $compose up -d --wait postgres spring spring-b | Out-Null
    docker compose -f $compose up -d --force-recreate --wait nginx | Out-Null
}
