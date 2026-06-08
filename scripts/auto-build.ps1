$MainClass = "terminal.Main"
$JarName = "server.jar"
$SourceIdPrefix = "AutoBuildJavaTerminalConsoleMonitor"

# Script nằm trong scripts/, repo root là thư mục cha
$ScriptDir = $PSScriptRoot
$RepoRoot = Split-Path $ScriptDir -Parent

$SourceDir = Join-Path $RepoRoot "src"
$BuildDir = Join-Path $RepoRoot "build/classes"
$DistDir = Join-Path $RepoRoot "dist"
$JarFile = Join-Path $DistDir $JarName
$ManifestFile = Join-Path $RepoRoot "manifest.txt"

$SourceIds = @(
    "$SourceIdPrefix-Changed",
    "$SourceIdPrefix-Created",
    "$SourceIdPrefix-Deleted",
    "$SourceIdPrefix-Renamed"
)

function Clear-AutoBuildEvents {
    foreach ($id in $SourceIds) {
        Get-EventSubscriber -SourceIdentifier $id -ErrorAction SilentlyContinue | Unregister-Event
        Get-Event -SourceIdentifier $id -ErrorAction SilentlyContinue | Remove-Event
    }
}

function Invoke-JarBuild {
    Write-Host "`n[+] Dang rebuild project..." -ForegroundColor Yellow

    Start-Sleep -Milliseconds 500

    if (!(Test-Path $SourceDir)) {
        Write-Host "[ERROR] Khong tim thay thu muc source: $SourceDir" -ForegroundColor Red
        return
    }

    $SourceFiles = Get-ChildItem $SourceDir -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName

    if ($SourceFiles.Count -eq 0) {
        Write-Host "[ERROR] Khong tim thay file .java nao trong: $SourceDir" -ForegroundColor Red
        return
    }

    # Tao thu muc build/dist
    New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

    # Xoa class cu
    Get-ChildItem $BuildDir -Recurse -Force -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

    # Bien dich tat ca file Java trong src/
    & javac -encoding UTF-8 -d $BuildDir $SourceFiles

    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Code Java bi loi, khong build duoc!" -ForegroundColor Red
        return
    }

    # Tao manifest, khong dung BOM
    [System.IO.File]::WriteAllText(
        $ManifestFile,
        "Main-Class: $MainClass`r`n",
        [System.Text.Encoding]::ASCII
    )

    # Dong goi JAR
    & jar cfm $JarFile $ManifestFile -C $BuildDir . > $null

    if ($LASTEXITCODE -eq 0) {
        $size = [Math]::Round((Get-Item $JarFile).Length / 1KB, 2)
        Write-Host "[SUCCESS] Da cap nhat $JarFile ($size KB)" -ForegroundColor Green
    } else {
        Write-Host "[ERROR] Loi khi tao file JAR!" -ForegroundColor Red
    }
}

Clear-AutoBuildEvents

New-Item -ItemType Directory -Force -Path $SourceDir | Out-Null
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

$Watcher = New-Object System.IO.FileSystemWatcher
$Watcher.Path = $SourceDir
$Watcher.Filter = "*.java"
$Watcher.NotifyFilter = [System.IO.NotifyFilters]'LastWrite, Size, FileName, CreationTime'
$Watcher.IncludeSubdirectories = $true
$Watcher.EnableRaisingEvents = $true

Register-ObjectEvent -InputObject $Watcher -EventName Changed -SourceIdentifier "$SourceIdPrefix-Changed" | Out-Null
Register-ObjectEvent -InputObject $Watcher -EventName Created -SourceIdentifier "$SourceIdPrefix-Created" | Out-Null
Register-ObjectEvent -InputObject $Watcher -EventName Deleted -SourceIdentifier "$SourceIdPrefix-Deleted" | Out-Null
Register-ObjectEvent -InputObject $Watcher -EventName Renamed -SourceIdentifier "$SourceIdPrefix-Renamed" | Out-Null

Write-Host ">>> Dang theo doi thu muc: $SourceDir" -ForegroundColor Cyan
Write-Host ">>> Moi lan luu file .java se build ra: $JarFile" -ForegroundColor Cyan
Write-Host ">>> Main-Class: $MainClass" -ForegroundColor DarkCyan
Write-Host ">>> Nhan Ctrl + C de dung." -ForegroundColor DarkGray

# Build 1 lan khi vua mo script
Invoke-JarBuild

try {
    while ($true) {
        $event = Wait-Event

        if ($event.SourceIdentifier -like "$SourceIdPrefix-*") {
            Remove-Event -EventIdentifier $event.EventIdentifier

            # Debounce: tranh build nhieu lan khi Ctrl + S
            Start-Sleep -Milliseconds 1000

            foreach ($id in $SourceIds) {
                Get-Event -SourceIdentifier $id -ErrorAction SilentlyContinue | Remove-Event
            }

            Invoke-JarBuild
        }
    }
}
finally {
    Clear-AutoBuildEvents

    $Watcher.EnableRaisingEvents = $false
    $Watcher.Dispose()

    Write-Host "`n>>> Da dung auto build. <<<" -ForegroundColor DarkGray
}