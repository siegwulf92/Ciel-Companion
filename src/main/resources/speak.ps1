# Ciel Companion TTS PowerShell Script v4
# - Reverts to basic Speak() method to fix silent failure with NaturalVoiceSAPIAdapter.
# - Keeps robust fallback and logging.

param(
    [Parameter(Mandatory=$true)][string]$Text,
    [Parameter(Mandatory=$true)][string]$VoiceName,
    [Parameter(Mandatory=$true)][int]$Rate,
    # SSML parameters are kept for future use but are not currently used in this version.
    [Parameter(Mandatory=$false)][string]$Style = "default",
    [Parameter(Mandatory=$false)][string]$Pitch = "+0%",
    [Parameter(Mandatory=$false)][string]$LangCode = "en-US"
)

# --- Logging Setup ---
$logPath = "C:\Ciel Companion\logs\ciel_tts_log.txt"
function Write-Log {
    param ([string]$Message)
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $logMessage = "[$timestamp] Ciel TTS Debug: $Message"
    # Use Add-Content with UTF8 encoding to ensure consistency and prevent corruption
    Add-Content -Path $logPath -Value $logMessage -Encoding utf8
}

# --- Diagnostic Mode ---
if (-not $PSBoundParameters.ContainsKey('Text')) {
    try {
        Add-Type -AssemblyName System.Speech
        $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
        $voiceListPath = "C:\Ciel Companion\logs\ciel_sapi_voices.txt"
        $synth.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name } | Out-File -FilePath $voiceListPath -Encoding utf8
        Write-Host "Voice list generated at $voiceListPath"
    } catch {
        Write-Error "Failed to generate voice list: $($_.Exception.Message)"
    }
    return
}


try {
    Add-Type -AssemblyName System.Speech -ErrorAction Stop
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer

    $voiceToUse = $VoiceName
    try {
        # Attempt to use the preferred (Natural) voice
        Write-Log "Attempting to use preferred voice '$VoiceName'."
        $synth.SelectVoice($VoiceName)
        Write-Log "Successfully selected preferred voice '$VoiceName'."
    } catch {
        # If the preferred voice fails, log it and switch to the fallback
        $fallbackVoice = "Microsoft Haruka Desktop"
        Write-Log "Preferred voice '$VoiceName' not found or failed to load. Attempting fallback to '$fallbackVoice'."
        try {
            $synth.SelectVoice($fallbackVoice)
            $voiceToUse = $fallbackVoice
            Write-Log "Successfully switched to fallback voice '$fallbackVoice'."
        } catch {
            Write-Log "FATAL: Fallback voice '$fallbackVoice' also failed to load. Cannot speak."
            Write-Error "PowerShell TTS Error: Both preferred and fallback voices failed."
            return # Exit script if no voices work
        }
    }
    
    # Use the simple, reliable Speak() method which works for all SAPI voices.
    # The complex SSML logic is temporarily removed to ensure stability.
    Write-Log "Speaking with basic TTS. Voice: '$voiceToUse', Rate: $Rate"
    $synth.Rate = $Rate
    $synth.Speak($Text)

    $synth.Dispose()
}
catch {
    Write-Log "An unexpected error occurred in the speech script: $($_.Exception.Message)"
    Write-Error "PowerShell TTS Error: $($_.Exception.Message)"
}

