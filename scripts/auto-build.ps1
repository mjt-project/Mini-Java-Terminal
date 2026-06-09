$SourceIdPrefix = "AutoBuildMiniJavaTerminal"

# srcipt nằm trong srcipts/, repo root là thư mục cha
$srciptDir = $PSsrciptRoot
$RepoRoot = Split-Path $srciptDir -Parent

$SourceDir = Join-Path $RepoRoot "src"
$DistDir = Join-Path $RepoRoot "dist"
$PomFile = Join-Path $RepoRoot "pom.xml"

$TargetJar = Join-Path $RepoRoot "target\server.jar"
$DistJar = Join-Path $DistDir "server.jar"

$SourceIds = @(
    "$SourceIdPrefix-Java-Changed",
    "$SourceIdPrefix-Java-Created",
    "$SourceIdPrefix-Java-Deleted",
    "$SourceIdPrefix-Java-Renamed",
    "$SourceIdPrefix-Pom-Changed"
)

function Clear-AutoBuildEvents {
    foreach ($id in $SourceIds) {
        Get-EventSubsrciber -SourceIdentifier $id -ErrorAction SilentlyContinue | Unregister-Event
        Get-Event -SourceIdentifier $id -ErrorAction SilentlyContinue | Remove-Event
    }
}

function Invoke-JarBuild {
    Write-Host "`n[+] Dang build bang Maven..." -ForegroundColor Yellow

    if (!(Test-Path $PomFile)) {
        Write-Host "[ERROR] Khong tim thay pom.xml: $PomFile" -ForegroundColor Red
        return
    }

    if (!(Test-Path $SourceDir)) {
        Write-Host "[ERROR] Khong tim thay thu muc source: $SourceDir" -ForegroundColor Red
        return
    }

    Push-Location $RepoRoot

    try {
        # Build bang Maven de gom dependency vao shaded jar
        & mvn -q -DskipTests package

        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] Maven build that bai!" -ForegroundColor Red
            return
        }

        if (!(Test-Path $TargetJar)) {
            Write-Host "[ERROR] Khong tim thay target jar: $TargetJar" -ForegroundColor Red
            return
        }

        New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
        Copy-Item $TargetJar $DistJar -Force

        $size = [Math]::Round((Get-Item $DistJar).Length / 1KB, 2)

        Write-Host "[SUCCESS] Da cap nhat $DistJar ($size KB)" -ForegroundColor Green
        Write-Host "[INFO] File dung de upload/chay: dist\server.jar" -ForegroundColor Cyan

    } finally {
        Pop-Location
    }
}

Clear-AutoBuildEvents

New-Item -ItemType Directory -Force -Path $SourceDir | Out-Null
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

# Watch file Java trong src/
$JavaWatcher = New-Object System.IO.FileSystemWatcher
$JavaWatcher.Path = $SourceDir
$JavaWatcher.Filter = "*.java"
$JavaWatcher.NotifyFilter = [System.IO.NotifyFilters]'LastWrite, Size, FileName, CreationTime'
$JavaWatcher.IncludeSubdirectories = $true
$JavaWatcher.EnableRaisingEvents = $true

Register-ObjectEvent -InputObject $JavaWatcher -EventName Changed -SourceIdentifier "$SourceIdPrefix-Java-Changed" | Out-Null
Register-ObjectEvent -InputObject $JavaWatcher -EventName Created -SourceIdentifier "$SourceIdPrefix-Java-Created" | Out-Null
Register-ObjectEvent -InputObject $JavaWatcher -EventName Deleted -SourceIdentifier "$SourceIdPrefix-Java-Deleted" | Out-Null
Register-ObjectEvent -InputObject $JavaWatcher -EventName Renamed -SourceIdentifier "$SourceIdPrefix-Java-Renamed" | Out-Null

# Watch pom.xml
$PomWatcher = New-Object System.IO.FileSystemWatcher
$PomWatcher.Path = $RepoRoot
$PomWatcher.Filter = "pom.xml"
$PomWatcher.NotifyFilter = [System.IO.NotifyFilters]'LastWrite, Size, FileName'
$PomWatcher.IncludeSubdirectories = $false
$PomWatcher.EnableRaisingEvents = $true

Register-ObjectEvent -InputObject $PomWatcher -EventName Changed -SourceIdentifier "$SourceIdPrefix-Pom-Changed" | Out-Null

Write-Host ">>> Mini Java Terminal Auto Build" -ForegroundColor Cyan
Write-Host ">>> Dang theo doi source: $SourceDir" -ForegroundColor Cyan
Write-Host ">>> Dang theo doi pom.xml: $PomFile" -ForegroundColor Cyan
Write-Host ">>> Output: $DistJar" -ForegroundColor Cyan
Write-Host ">>> Build tool: Maven" -ForegroundColor DarkCyan
Write-Host ">>> Nhan Ctrl + C de dung." -ForegroundColor DarkGray

# Build 1 lan khi vua mo srcipt
Invoke-JarBuild

try {
    while ($true) {
        $autoBuildEvent = Wait-Event

        if ($autoBuildEvent.SourceIdentifier -like "$SourceIdPrefix-*") {
            Remove-Event -EventIdentifier $autoBuildEvent.EventIdentifier

            # Debounce: tranh build nhieu lan khi Ctrl + S
            Start-Sleep -Milliseconds 1200

            foreach ($id in $SourceIds) {
                Get-Event -SourceIdentifier $id -ErrorAction SilentlyContinue | Remove-Event
            }

            Invoke-JarBuild
        }
    }
}
finally {
    Clear-AutoBuildEvents

    $JavaWatcher.EnableRaisingEvents = $false
    $JavaWatcher.Dispose()

    $PomWatcher.EnableRaisingEvents = $false
    $PomWatcher.Dispose()

    Write-Host "`n>>> Da dung auto build. <<<" -ForegroundColor DarkGray
}