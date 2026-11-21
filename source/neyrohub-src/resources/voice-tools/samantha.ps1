param(
    [Parameter(Mandatory = $true)][string]$Text,      # plain text; '|' marks a short pause
    [Parameter(Mandatory = $true)][string]$Out,       # output .wav (48 kHz mono, processed)
    [double]$Semitones = 0.8,
    [string]$Pitch = "+14%",
    [string]$Rate = "-8%"
)
# Samantha, the NeyroCraft core: Russian female TTS (Irina) + feminine/AI colour (SamanthaFx).
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not ('SamanthaFx' -as [type])) { Add-Type -Path (Join-Path $here 'SamanthaFx.cs') }
Add-Type -AssemblyName System.Speech

$esc = [System.Security.SecurityElement]::Escape($Text) -replace '\|', '<break time="380ms"/>'
$ssml = @"
<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="ru-RU">
  <prosody pitch="$Pitch" rate="$Rate" volume="90">$esc</prosody>
</speak>
"@
$raw = [IO.Path]::ChangeExtension($Out, '.raw.wav')
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice('Microsoft Irina Desktop')
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(48000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$s.SetOutputToWaveFile($raw, $fmt)
$s.SpeakSsml($ssml)
$s.Dispose()
[SamanthaFx]::Process($raw, $Out, $Semitones)
Remove-Item $raw -Force
$len = (Get-Item $Out).Length - 44
"OK " + [math]::Round($len / 96000.0, 3)
