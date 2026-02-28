package com.cielcompanion.service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class Settings {

    private static String VOICE_NAME_HINT;
    private static String VOICE_LANGUAGE_CODE;
    private static int TTS_RATE;
    private static int TTS_RATE_ANNOYED;
    private static long CHECK_INTERVAL_MS;
    private static boolean LOGGING_SHOW_VERBOSE_STATUS;
    private static long FIRST_GREETING_DELAY_SECONDS;
    private static long LOGIN_GREETING_DELAY_SECONDS;
    private static int MIN_GLOBAL_GAP_SEC;
    
    private static int PHASE1_THRESHOLD_MIN;
    private static int PHASE2_THRESHOLD_MIN;
    private static int PHASE3_THRESHOLD_MIN;
    private static int PHASE4_THRESHOLD_MIN;
    private static int PHASE1_MIN_GAP_SEC;
    private static int PHASE1_MAX_GAP_SEC;
    private static int PHASE2_MIN_GAP_SEC;
    private static int PHASE2_MAX_GAP_SEC;
    private static int PHASE3_MIN_GAP_SEC;
    private static int PHASE3_MAX_GAP_SEC;
    private static int PHASE4_MIN_GAP_SEC;
    private static int PHASE4_MAX_GAP_SEC;
    
    private static int SPECIAL_EVENT_INTER_SPEECH_MIN_SEC;
    private static int SPECIAL_EVENT_INTER_SPEECH_MAX_SEC;
    
    private static int RARE_CHANCE_PHASE1;
    private static int RARE_CHANCE_PHASE2;
    private static int RARE_CHANCE_PHASE3;
    private static int PHASE3_GAME_RARE_CHANCE;
    
    private static String BROWSER_PROCESSES_REGEX;
    private static String STREAMING_TITLE_REGEX;
    private static String PLAYER_PROCESSES_REGEX;
    private static List<String> HARD_MUTE_PROCS;
    private static List<String> AWARENESS_EXCLUSIONS;
    private static int MUTE_PERSISTENCE_THRESHOLD_MS;

    private static boolean HOTKEY_ENABLED;
    private static String HOTKEY_KEY;
    
    private static String DND_CAMPAIGN_PATH;

    private static String ASTRONOMY_API_APPLICATION_ID;
    private static String ASTRONOMY_API_APPLICATION_SECRET;
    private static String IPGEOLOCATION_API_KEY;

    private static String AZURE_SPEECH_KEY;
    private static String AZURE_SPEECH_REGION;
    private static String AZURE_VOICE_NAME;
    private static long AZURE_MONTHLY_LIMIT_HOURS; 

    // --- NEW: AI ORCHESTRATION SETTINGS ---
    private static String LLM_PERSONALITY_URL; 
    private static String LLM_PERSONALITY_MODEL; 
    private static String LLM_EVALUATOR_URL; 
    private static String LLM_EVALUATOR_MODEL; 
    private static String LLM_LOGIC_URL; 
    private static String LLM_LOGIC_MODEL; 
    private static String LLM_ONLINE_FALLBACK_URL; 
    private static String LLM_ONLINE_FALLBACK_KEY;
    private static boolean AI_OBSERVER_ENABLED;

    public static void initialize() {
        Properties props = new Properties();
        
        try (InputStream is = Settings.class.getResourceAsStream("/ciel_settings.properties")) {
            if (is == null) throw new RuntimeException("ciel_settings.properties not found on classpath");
            try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                props.load(isr);
            }
        } catch (Exception e) {
            System.err.println("Ciel FATAL ERROR: Failed to load settings from ciel_settings.properties.");
            throw new RuntimeException(e);
        }

        try (InputStream isSecrets = Settings.class.getResourceAsStream("/ciel_secrets.properties")) {
            if (isSecrets != null) {
                try (InputStreamReader isrSecrets = new InputStreamReader(isSecrets, StandardCharsets.UTF_8)) {
                    props.load(isrSecrets); 
                    System.out.println("Ciel Debug: Secrets file loaded successfully.");
                }
            } else {
                System.out.println("Ciel Warning: ciel_secrets.properties not found. API features may be disabled.");
            }
        } catch (Exception e) {
            System.err.println("Ciel Warning: Failed to load secrets file.");
        }

        try {
            VOICE_NAME_HINT = props.getProperty("ciel.voiceNameHint", "Microsoft Haruka Desktop");
            VOICE_LANGUAGE_CODE = props.getProperty("ciel.voice.LanguageCode", "ja-JP");
            TTS_RATE = Integer.parseInt(props.getProperty("ciel.ttsRate", "0"));
            TTS_RATE_ANNOYED = Integer.parseInt(props.getProperty("ciel.ttsRate.annoyed", "-2"));
            CHECK_INTERVAL_MS = Long.parseLong(props.getProperty("ciel.checkIntervalMs", "50"));
            LOGGING_SHOW_VERBOSE_STATUS = Boolean.parseBoolean(props.getProperty("ciel.logging.showVerboseStatus", "false"));
            FIRST_GREETING_DELAY_SECONDS = Long.parseLong(props.getProperty("ciel.firstGreetingDelaySeconds", "5"));
            LOGIN_GREETING_DELAY_SECONDS = Long.parseLong(props.getProperty("ciel.loginGreetingDelaySeconds", "120"));
            MIN_GLOBAL_GAP_SEC = Integer.parseInt(props.getProperty("ciel.minGlobalGapSec", "5"));

            PHASE1_THRESHOLD_MIN = Integer.parseInt(props.getProperty("ciel.phase1ThresholdMin", "5"));
            PHASE2_THRESHOLD_MIN = Integer.parseInt(props.getProperty("ciel.phase2ThresholdMin", "10"));
            PHASE3_THRESHOLD_MIN = Integer.parseInt(props.getProperty("ciel.phase3ThresholdMin", "20"));
            PHASE4_THRESHOLD_MIN = Integer.parseInt(props.getProperty("ciel.phase4ThresholdMin", "27"));

            PHASE1_MIN_GAP_SEC = Integer.parseInt(props.getProperty("ciel.phase1MinGapSec", "240"));
            PHASE1_MAX_GAP_SEC = Integer.parseInt(props.getProperty("ciel.phase1MaxGapSec", "360"));
            PHASE2_MIN_GAP_SEC = Integer.parseInt(props.getProperty("ciel.phase2MinGapSec", "180"));
            PHASE2_MAX_GAP_SEC = Integer.parseInt(props.getProperty("ciel.phase2MaxGapSec", "300"));
            PHASE3_MIN_GAP_SEC = Integer.parseInt(props.getProperty("ciel.phase3MinGapSec", "120"));
            PHASE3_MAX_GAP_SEC = Integer.parseInt(props.getProperty("ciel.phase3MaxGapSec", "240"));
            PHASE4_MIN_GAP_SEC = Integer.parseInt(props.getProperty("ciel.phase4MinGapSec", "3"));
            PHASE4_MAX_GAP_SEC = Integer.parseInt(props.getProperty("ciel.phase4MaxGapSec", "5"));

            SPECIAL_EVENT_INTER_SPEECH_MIN_SEC = Integer.parseInt(props.getProperty("ciel.specialEventInterSpeechMinGapSec", "2"));
            SPECIAL_EVENT_INTER_SPEECH_MAX_SEC = Integer.parseInt(props.getProperty("ciel.specialEventInterSpeechMaxSec", "5"));

            RARE_CHANCE_PHASE1 = Integer.parseInt(props.getProperty("ciel.rareChancePhase1", "4"));
            RARE_CHANCE_PHASE2 = Integer.parseInt(props.getProperty("ciel.rareChancePhase2", "7"));
            RARE_CHANCE_PHASE3 = Integer.parseInt(props.getProperty("ciel.rareChancePhase3", "20"));
            PHASE3_GAME_RARE_CHANCE = Integer.parseInt(props.getProperty("ciel.phase3GameRareChance", "10"));

            BROWSER_PROCESSES_REGEX = props.getProperty("ciel.browserProcessesRegex", "(?i)chrome\\.exe|msedge\\.exe|firefox\\.exe");
            STREAMING_TITLE_REGEX = props.getProperty("ciel.streamingTitleRegex", "(?i)YouTube|Netflix|Twitch");
            PLAYER_PROCESSES_REGEX = props.getProperty("ciel.playerProcessesRegex", "(?i)vlc\\.exe|mpv\\.exe");
            HARD_MUTE_PROCS = Arrays.asList(props.getProperty("ciel.hardMuteProcs", "").split(","));
            AWARENESS_EXCLUSIONS = Arrays.asList(props.getProperty("ciel.awareness.excludeProcs", "steam.exe,explorer.exe").toLowerCase().split(","));
            MUTE_PERSISTENCE_THRESHOLD_MS = Integer.parseInt(props.getProperty("ciel.mutePersistenceThresholdMs", "1500"));
            
            HOTKEY_ENABLED = Boolean.parseBoolean(props.getProperty("ciel.hotkey.enabled", "true"));
            HOTKEY_KEY = props.getProperty("ciel.hotkey.key", "F12");
            
            DND_CAMPAIGN_PATH = props.getProperty("dnd.campaignPath", "");
            
            ASTRONOMY_API_APPLICATION_ID = props.getProperty("astronomy.api.applicationId", "");
            ASTRONOMY_API_APPLICATION_SECRET = props.getProperty("astronomy.api.applicationSecret", "");
            IPGEOLOCATION_API_KEY = props.getProperty("api.ipgeolocation.key", ""); 

            AZURE_SPEECH_KEY = props.getProperty("azure.speech.key", "");
            AZURE_SPEECH_REGION = props.getProperty("azure.speech.region", "");
            AZURE_VOICE_NAME = props.getProperty("azure.speech.voiceName", "ja-JP-NanamiNeural");
            AZURE_MONTHLY_LIMIT_HOURS = Long.parseLong(props.getProperty("azure.speech.limitHours", "5"));

            AzureUsageTracker.setLimit(AZURE_MONTHLY_LIMIT_HOURS * 3600);

            // --- AI ORCHESTRATION ---
            // Fast Chat (GPU)
            LLM_PERSONALITY_URL = props.getProperty("ciel.ai.personalityUrl", "http://localhost:11434/v1"); 
            LLM_PERSONALITY_MODEL = props.getProperty("ciel.ai.personalityModel", "gemma2"); 
            
            // Background Observer (CPU)
            LLM_EVALUATOR_URL = props.getProperty("ciel.ai.evaluatorUrl", "http://localhost:11434/v1"); 
            LLM_EVALUATOR_MODEL = props.getProperty("ciel.ai.evaluatorModel", "qwen2.5:3b");
            
            // Deep Logic (CPU/LM Studio)
            LLM_LOGIC_URL = props.getProperty("ciel.ai.logicUrl", "http://localhost:1234/v1");
            LLM_LOGIC_MODEL = props.getProperty("ciel.ai.logicModel", "phi-4");
            
            // Fallback
            LLM_ONLINE_FALLBACK_URL = props.getProperty("ciel.ai.fallbackUrl", "https://api.openai.com/v1");
            LLM_ONLINE_FALLBACK_KEY = props.getProperty("ciel.ai.fallbackKey", "");
            AI_OBSERVER_ENABLED = Boolean.parseBoolean(props.getProperty("ciel.ai.observerEnabled", "true"));

            System.out.println("Ciel Debug: Settings loaded successfully.");

        } catch (Exception e) {
            System.err.println("Ciel FATAL ERROR: Failed to parse settings.");
            throw new RuntimeException(e);
        }
    }

    public static String getVoiceNameHint() { return VOICE_NAME_HINT; }
    public static String getVoiceLanguageCode() { return VOICE_LANGUAGE_CODE; }
    public static int getTtsRate() { return TTS_RATE; }
    public static int getTtsRateAnnoyed() { return TTS_RATE_ANNOYED; }
    public static long getCheckIntervalMs() { return CHECK_INTERVAL_MS; }
    public static boolean isVerboseLoggingEnabled() { return LOGGING_SHOW_VERBOSE_STATUS; }
    public static long getFirstGreetingDelaySeconds() { return FIRST_GREETING_DELAY_SECONDS; }
    public static long getLoginGreetingDelaySeconds() { return LOGIN_GREETING_DELAY_SECONDS; }
    public static int getMinGlobalGapSec() { return MIN_GLOBAL_GAP_SEC; }
    public static int getPhase1ThresholdMin() { return PHASE1_THRESHOLD_MIN; }
    public static int getPhase2ThresholdMin() { return PHASE2_THRESHOLD_MIN; }
    public static int getPhase3ThresholdMin() { return PHASE3_THRESHOLD_MIN; }
    public static int getPhase4ThresholdMin() { return PHASE4_THRESHOLD_MIN; }
    public static int getPhase1MinGapSec() { return PHASE1_MIN_GAP_SEC; }
    public static int getPhase1MaxGapSec() { return PHASE1_MAX_GAP_SEC; }
    public static int getPhase2MinGapSec() { return PHASE2_MIN_GAP_SEC; }
    public static int getPhase2MaxGapSec() { return PHASE2_MAX_GAP_SEC; }
    public static int getPhase3MinGapSec() { return PHASE3_MIN_GAP_SEC; }
    public static int getPhase3MaxGapSec() { return PHASE3_MAX_GAP_SEC; }
    public static int getPhase4MinGapSec() { return PHASE4_MIN_GAP_SEC; }
    public static int getPhase4MaxGapSec() { return PHASE4_MAX_GAP_SEC; }
    public static int getSpecialEventInterSpeechMinSec() { return SPECIAL_EVENT_INTER_SPEECH_MIN_SEC; }
    public static int getSpecialEventInterSpeechMaxSec() { return SPECIAL_EVENT_INTER_SPEECH_MAX_SEC; }
    public static int getRareChancePhase1() { return RARE_CHANCE_PHASE1; }
    public static int getRareChancePhase2() { return RARE_CHANCE_PHASE2; }
    public static int getRareChancePhase3() { return RARE_CHANCE_PHASE3; }
    public static int getPhase3GameRareChance() { return PHASE3_GAME_RARE_CHANCE; }
    public static String getBrowserProcessesRegex() { return BROWSER_PROCESSES_REGEX; }
    public static String getStreamingTitleRegex() { return STREAMING_TITLE_REGEX; }
    public static String getPlayerProcessesRegex() { return PLAYER_PROCESSES_REGEX; }
    public static List<String> getHardMuteProcs() { return HARD_MUTE_PROCS; }
    public static List<String> getAwarenessExclusions() { return AWARENESS_EXCLUSIONS; }
    public static int getMutePersistenceThresholdMs() { return MUTE_PERSISTENCE_THRESHOLD_MS; }
    public static boolean isHotkeyEnabled() { return HOTKEY_ENABLED; }
    public static String getHotkeyKey() { return HOTKEY_KEY; }
    public static String getDndCampaignPath() { return DND_CAMPAIGN_PATH; }
    public static String getAstronomyApiApplicationId() { return ASTRONOMY_API_APPLICATION_ID; }
    public static String getAstronomyApiApplicationSecret() { return ASTRONOMY_API_APPLICATION_SECRET; }
    public static String getIpGeolocationApiKey() { return IPGEOLOCATION_API_KEY; }
    public static String getAzureSpeechKey() { return AZURE_SPEECH_KEY; }
    public static String getAzureSpeechRegion() { return AZURE_SPEECH_REGION; }
    public static String getAzureVoiceName() { return AZURE_VOICE_NAME; }

    public static String getLlmPersonalityUrl() { return LLM_PERSONALITY_URL; }
    public static String getLlmPersonalityModel() { return LLM_PERSONALITY_MODEL; }
    public static String getLlmEvaluatorUrl() { return LLM_EVALUATOR_URL; }
    public static String getLlmEvaluatorModel() { return LLM_EVALUATOR_MODEL; }
    public static String getLlmLogicUrl() { return LLM_LOGIC_URL; }
    public static String getLlmLogicModel() { return LLM_LOGIC_MODEL; }
    public static String getLlmOnlineFallbackUrl() { return LLM_ONLINE_FALLBACK_URL; }
    public static String getLlmOnlineFallbackKey() { return LLM_ONLINE_FALLBACK_KEY; }
    public static boolean isAiObserverEnabled() { return AI_OBSERVER_ENABLED; }
}