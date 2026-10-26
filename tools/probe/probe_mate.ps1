# FiilPods real-device protocol probe (ASCII only to avoid console codepage issues).
# Usage: powershell -ExecutionPolicy Bypass -File probe_mate.ps1 -Port COM4
param(
    [string]$Port = 'COM4',
    [int]$Baud = 9600,
    [int]$WaitMs = 1500
)

$ErrorActionPreference = 'Stop'

function Hex([byte[]]$b) {
    if ($null -eq $b -or $b.Length -eq 0) { return '<empty>' }
    return (($b | ForEach-Object { $_.ToString('X2') }) -join ' ')
}

# Mate frame, identical to pods/FiilMate.kt buildFrame():
#   cmdId != 0 : 00 cid 01 00 (payload.len+2) cmdId payload.len payload...
#   cmdId == 0 : 00 cid 01 00 payload.len payload...
function Build-MateFrame([byte]$cid, [byte]$cmdId, [byte[]]$payload) {
    if ($null -eq $payload) { $payload = [byte[]]@() }
    $ms = New-Object System.IO.MemoryStream
    $ms.WriteByte(0x00)
    $ms.WriteByte($cid)
    $ms.WriteByte(0x01)
    $ms.WriteByte(0x00)
    if ($cmdId -ne 0) {
        $ms.WriteByte([byte]($payload.Length + 2))
        $ms.WriteByte($cmdId)
        $ms.WriteByte([byte]$payload.Length)
    } else {
        $ms.WriteByte([byte]$payload.Length)
    }
    if ($payload.Length -gt 0) { $ms.Write($payload, 0, $payload.Length) }
    return $ms.ToArray()
}

# GAIA frame: FF F1 00 len 02 34 cmdH cmdL payload...
function Build-Gaia([int]$cmd, [byte[]]$payload) {
    if ($null -eq $payload) { $payload = [byte[]]@() }
    $ms = New-Object System.IO.MemoryStream
    $ms.WriteByte(0xFF); $ms.WriteByte(0xF1); $ms.WriteByte(0x00)
    $ms.WriteByte([byte]$payload.Length)
    $ms.WriteByte(0x02); $ms.WriteByte(0x34)
    $ms.WriteByte([byte](($cmd -shr 8) -band 0xFF)); $ms.WriteByte([byte]($cmd -band 0xFF))
    if ($payload.Length -gt 0) { $ms.Write($payload, 0, $payload.Length) }
    return $ms.ToArray()
}

function Send-Frame([System.IO.Ports.SerialPort]$sp, [string]$label, [byte[]]$frame) {
    Write-Host ''
    Write-Host "==> $label"
    Write-Host ("    TX: " + (Hex $frame))
    $sp.DiscardInBuffer()
    $sp.Write($frame, 0, $frame.Length)
    Start-Sleep -Milliseconds $WaitMs
    $n = $sp.BytesToRead
    if ($n -le 0) {
        Write-Host '    RX: <no response>'
        return
    }
    $buf = New-Object byte[] $n
    $sp.Read($buf, 0, $n) | Out-Null
    Write-Host ("    RX($n): " + (Hex $buf))
}

$sp = New-Object System.IO.Ports.SerialPort($Port, $Baud, 'None', 8, 'One')
$sp.ReadTimeout = 800
$sp.WriteTimeout = 800
try {
    $sp.Open()
} catch {
    Write-Host ("Cannot open " + $Port + " : " + $_.Exception.Message)
    exit 1
}
Write-Host ("Opened " + $Port + " @ " + $Baud)
Start-Sleep -Milliseconds 200

try {
    # ---- Mate queries (cid=39 QUERY) ----
    Send-Frame $sp 'QUERY.VERSION     (cid=39 cmdId=2)'   (Build-MateFrame 39 2  $null)
    Send-Frame $sp 'QUERY.BATTERY     (cid=39 cmdId=1)'   (Build-MateFrame 39 1  $null)
    Send-Frame $sp 'QUERY.MAF_STATUS  (cid=39 cmdId=12)'  (Build-MateFrame 39 12 $null)
    Send-Frame $sp 'QUERY.EQ_STATUS   (cid=39 cmdId=4)'   (Build-MateFrame 39 4  $null)
    Send-Frame $sp 'QUERY.LOW_LATENCY (cid=39 cmdId=8)'   (Build-MateFrame 39 8  $null)
    Send-Frame $sp 'QUERY.MULTIPOINT  (cid=39 cmdId=128)' (Build-MateFrame 39 128 $null)

    # ---- GAIA queries (legacy, check whether it also answers) ----
    Send-Frame $sp 'GAIA GET_EARTYPE  (cmd=0x0309=777)'   (Build-Gaia 777 $null)
    Send-Frame $sp 'GAIA GET_BATTERY  (cmd=0x0303=771)'   (Build-Gaia 771 $null)
}
finally {
    $sp.Close()
    $sp.Dispose()
    Write-Host ''
    Write-Host 'Port closed'
}
