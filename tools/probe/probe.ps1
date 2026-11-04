# Diptych KH1 probe client (Windows PowerShell 5.1, no modules).
# Connects to the KH1 mod on ws://127.0.0.1:<Port>, sends hello then one request, and prints each
# JSON reply on its own line. No Origin header is sent (the mod rejects any Origin).
# Exit codes: 0 ok, 1 connection failure or 5 s timeout, 2 bad arguments.
#
# From WSL:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(wslpath -w tools/probe/probe.ps1)" -Command state
#   ... -Command probe -Offset 0x233FE94 -Length 4
#   ... -Command press -ByteIndex 0 -Mask 16 -DurationMs 100
#   ... -Command log
param(
    [Parameter(Mandatory = $true)][ValidateSet('state', 'probe', 'press', 'log')][string]$Command,
    [long]$Offset = 0,
    [int]$Length = 4,
    [int]$ByteIndex = 0,
    [int]$Mask = 0,
    [int]$DurationMs = 100,
    [int]$Port = 7970
)
$ErrorActionPreference = 'Stop'
$TimeoutMs = 5000

$ws = New-Object System.Net.WebSockets.ClientWebSocket
$cts = New-Object System.Threading.CancellationTokenSource
$cts.CancelAfter($TimeoutMs)
$ct = $cts.Token

function Send-Json([string]$text) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    $seg = New-Object 'System.ArraySegment[byte]' -ArgumentList (, $bytes)
    $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).GetAwaiter().GetResult() | Out-Null
}

function Receive-Json {
    $buf = New-Object byte[] 65536
    $ms = New-Object System.IO.MemoryStream
    do {
        $seg = New-Object 'System.ArraySegment[byte]' -ArgumentList (, $buf)
        $r = $ws.ReceiveAsync($seg, $ct).GetAwaiter().GetResult()
        if ($r.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            throw 'server closed the connection'
        }
        $ms.Write($buf, 0, $r.Count)
    } while (-not $r.EndOfMessage)
    return [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
}

# Read frames until one has the wanted type (printing each); returns when found.
function Wait-For([string]$type) {
    while ($true) {
        $line = Receive-Json
        Write-Output $line
        if (($line | ConvertFrom-Json).type -eq $type) { return }
    }
}

try {
    $ws.ConnectAsync([Uri]"ws://127.0.0.1:$Port", $ct).GetAwaiter().GetResult() | Out-Null

    Send-Json '{"type":"hello","client":"diptych-probe","protocol":0}'
    Wait-For 'welcome'

    switch ($Command) {
        'state' {
            Send-Json '{"type":"subscribe","topics":["kh1.spike"],"rateHz":5}'
            Wait-For 'state'
        }
        'probe' {
            Send-Json ('{{"type":"command","id":"p1","name":"kh1.probe","args":{{"offset":{0},"length":{1}}}}}' -f $Offset, $Length)
            Wait-For 'result'
        }
        'press' {
            Send-Json ('{{"type":"command","id":"p1","name":"kh1.pressInput","args":{{"byteIndex":{0},"mask":{1},"durationMs":{2}}}}}' -f $ByteIndex, $Mask, $DurationMs)
            Wait-For 'result'
        }
        'log' {
            Send-Json '{"type":"command","id":"p1","name":"kh1.log","args":{}}'
            Wait-For 'result'
        }
    }
    try { $ws.CloseOutputAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, '', [System.Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null } catch { }
    exit 0
}
catch {
    $msg = $_.Exception.Message
    if ($cts.IsCancellationRequested) { $msg = "timeout after $TimeoutMs ms" }
    [Console]::Error.WriteLine("probe failed: $msg")
    exit 1
}
finally {
    $ws.Dispose()
    $cts.Dispose()
}
