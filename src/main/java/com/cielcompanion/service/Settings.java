package com.cielcompanion.service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Locale;

public class Settings {

    private static final Object SETTINGS_LOCK = new Object();
    private static volatile long profileLastModified = Long.MIN_VALUE;
    private static volatile long secretsLastModified = Long.MIN_VALUE;
    private static volatile long lastReloadCheck;
    private static String COMPANION_NAME;
    private static String COMPANION_PERSONA;
    private static String TTS_PRIMARY_PROVIDER;
    private static String TTS_FALLBACK_PROVIDER;
    private static String TTS_INSTRUCTIONS;

    private static String VOICE_NAME_HINT;
    private static String VOICE_LANGUAGE_CODE;
    private static int TTS_RATE;
    private static int TTS_RATE_ANNOYED;
    private static long CHECK_INTERVAL_MS;
    private static boolean LOGGING_SHOW_VERBOSE_STATUS;
    private static long FIRST_GREETING_DELAY_SECONDS;
    private static long LOGIN_GREETING_DELAY_SECONDS;
    private static long UPDATE_STATUS_DELAY_SECONDS;
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
    private static String DND_KNOWLEDGE_MODE;
    private static String DND_LIVE_STATE_PATH;

    private static String ASTRONOMY_API_APPLICATION_ID;
    private static String ASTRONOMY_API_APPLICATION_SECRET;
    private static String IPGEOLOCATION_API_KEY;

    private static String AZURE_SPEECH_KEY;
    private static String AZURE_SPEECH_REGION;
    private static String AZURE_VOICE_NAME;
    private static long AZURE_MONTHLY_LIMIT_HOURS; 
    private static long AZURE_MONTHLY_LIMIT_CHARACTERS;

    // BreezeBlue is deliberately environment-first: API keys must not be
    // committed to ciel_secrets.properties or copied into paired releases.
    private static String BREEZE_API_KEY;
    private static String BREEZE_VOICE_ID;
    private static String BREEZE_MODEL;
    private static String BREEZE_LANGUAGE_CODE;
    private static boolean BREEZE_ENABLED;
    private static boolean BREEZE_PREFER_ALL;
    private static boolean BREEZE_CACHE_ENABLED;
    private static boolean BREEZE_PREFETCH_ENABLED;
    private static boolean BREEZE_DYNAMIC_PRIMARY;
    private static long BREEZE_WARMUP_MIN_ENTRIES;
    private static boolean BREEZE_PRIME_OUTPUT;
    private static long BREEZE_DAILY_CREDIT_LIMIT;

    // --- NEW: AI ORCHESTRATION SETTINGS ---
    private static String LLM_PERSONALITY_URL; 
    private static String LLM_PERSONALITY_MODEL; 
    private static String LLM_EVALUATOR_URL; 
    private static String LLM_EVALUATOR_MODEL; 
    private static String LLM_LOGIC_URL; 
    private static String LLM_LOGIC_MODEL; 
    
    private static String LLM_LOCAL_LOGIC_FALLBACK_URL; 
    private static String LLM_LOCAL_LOGIC_FALLBACK_MODEL; 
    
    private static String LLM_ONLINE_FALLBACK_URL; 
    private static String LLM_ONLINE_FALLBACK_KEY;
    private static boolean AI_OBSERVER_ENABLED;

    public static void initialize() {
        synchronized (SETTINGS_LOCK) {
            loadSettings();
        }
    }

    private static void loadSettings() {
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

        // Secrets are external to the application/release tree. An explicit
        // CIEL_SECRETS_PATH may be supplied for paired machines; otherwise
        // use the per-user location protected by Windows ACLs.
        String configuredSecrets = System.getenv("CIEL_SECRETS_PATH");
        Path externalSecrets = configuredSecrets != null && !configuredSecrets.isBlank()
                ? Path.of(configuredSecrets)
                : Path.of(System.getProperty("user.home"), ".cielcompanion", "secrets.properties");
        try {
            if (Files.isRegularFile(externalSecrets)) {
                try (InputStream isSecrets = Files.newInputStream(externalSecrets);
                     InputStreamReader isrSecrets = new InputStreamReader(isSecrets, StandardCharsets.UTF_8)) {
                    props.load(isrSecrets);
                    System.out.println("Ciel Debug: External secrets file loaded (path withheld).");
                }
            } else {
                System.out.println("Ciel Warning: External secrets file unavailable; API features may be disabled.");
            }
        } catch (Exception e) {
            System.err.println("Ciel Warning: Failed to load external secrets file; API features may be disabled.");
        }

        // Non-secret user overrides live outside the JAR so a profile can be
        // customized per person/device. Secret-looking keys are rejected from
        // this layer and remain restricted to the protected secrets file.
        Path profile = resolveProfilePath();
        try {
            if (Files.isRegularFile(profile)) {
                Properties overrides = new Properties();
                try (InputStream input = Files.newInputStream(profile);
                     InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    overrides.load(reader);
                }
                for (String key : overrides.stringPropertyNames()) {
                    if (!isSensitiveProperty(key)) props.setProperty(key, overrides.getProperty(key));
                }
                System.out.println("Ciel Debug: External companion profile loaded.");
            }
        } catch (Exception e) {
            System.err.println("Ciel Warning: Failed to load companion profile; using prior settings: "
                    + e.getClass().getSimpleName());
        }

        try {
            COMPANION_NAME = props.getProperty("ciel.companion.name", "Ciel");
            COMPANION_PERSONA = props.getProperty("ciel.companion.persona",
                    "warm, attentive, expressive personal AI companion");
            TTS_PRIMARY_PROVIDER = props.getProperty("ciel.tts.primaryProvider", "breeze");
            TTS_FALLBACK_PROVIDER = props.getProperty("ciel.tts.fallbackProvider", "azure");
            TTS_INSTRUCTIONS = props.getProperty("ciel.tts.instructions", "");
            VOICE_NAME_HINT = props.getProperty("ciel.voiceNameHint", "Microsoft Haruka Desktop");
            VOICE_LANGUAGE_CODE = props.getProperty("ciel.voice.LanguageCode", "ja-JP");
            TTS_RATE = Integer.parseInt(props.getProperty("ciel.ttsRate", "0"));
            TTS_RATE_ANNOYED = Integer.parseInt(props.getProperty("ciel.ttsRate.annoyed", "-2"));
            CHECK_INTERVAL_MS = Long.parseLong(props.getProperty("ciel.checkIntervalMs", "50"));
            LOGGING_SHOW_VERBOSE_STATUS = Boolean.parseBoolean(props.getProperty("ciel.logging.showVerboseStatus", "false"));
            FIRST_GREETING_DELAY_SECONDS = Long.parseLong(props.getProperty("ciel.firstGreetingDelaySeconds", "5"));
            LOGIN_GREETING_DELAY_SECONDS = Long.parseLong(props.getProperty("ciel.loginGreetingDelaySeconds", "120"));
            UPDATE_STATUS_DELAY_SECONDS = Math.max(5, Math.min(10,
                    Long.parseLong(props.getProperty("ciel.updateStatusDelaySeconds", "7"))));
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
            DND_KNOWLEDGE_MODE = props.getProperty("dnd.knowledgeMode", "player").trim().toLowerCase(Locale.ROOT);
            if (!DND_KNOWLEDGE_MODE.equals("player") && !DND_KNOWLEDGE_MODE.equals("dm")) {
                DND_KNOWLEDGE_MODE = "player";
            }
            DND_LIVE_STATE_PATH = props.getProperty("dnd.liveStatePath", "").trim();
            
            ASTRONOMY_API_APPLICATION_ID = props.getProperty("astronomy.api.applicationId", "");
            ASTRONOMY_API_APPLICATION_SECRET = props.getProperty("astronomy.api.applicationSecret", "");
            IPGEOLOCATION_API_KEY = props.getProperty("api.ipgeolocation.key", ""); 

            AZURE_SPEECH_KEY = props.getProperty("azure.speech.key", "");
            AZURE_SPEECH_REGION = props.getProperty("azure.speech.region", "");
            AZURE_VOICE_NAME = props.getProperty("azure.speech.voiceName", "ja-JP-NanamiNeural");
            AZURE_MONTHLY_LIMIT_HOURS = Long.parseLong(props.getProperty("azure.speech.limitHours", "5"));
            AZURE_MONTHLY_LIMIT_CHARACTERS = Long.parseLong(props.getProperty("azure.speech.limitCharacters", "450000"));

            // Accept both the documented lowercase property and the
            // conventional uppercase secret-file name. Environment wins.
            BREEZE_API_KEY = firstNonBlank(System.getenv("BREEZE_API_KEY"),
                    firstNonBlank(props.getProperty("breeze.api.key", ""),
                            props.getProperty("BREEZE_API_KEY", "")));
            BREEZE_VOICE_ID = firstNonBlank(System.getenv("BREEZE_VOICE_ID"),
                    props.getProperty("breeze.tts.voiceId", ""));
            BREEZE_MODEL = props.getProperty("breeze.tts.model", "breeze-tts-2");
            BREEZE_LANGUAGE_CODE = props.getProperty("breeze.tts.languageCode", "en");
            BREEZE_ENABLED = Boolean.parseBoolean(props.getProperty("breeze.tts.enabled", "true"));
            BREEZE_PREFER_ALL = Boolean.parseBoolean(props.getProperty("breeze.tts.preferAll", "true"));
            BREEZE_CACHE_ENABLED = Boolean.parseBoolean(props.getProperty("breeze.tts.cache", "true"));
            BREEZE_PREFETCH_ENABLED = Boolean.parseBoolean(props.getProperty("breeze.tts.prefetch", "false"));
            BREEZE_DYNAMIC_PRIMARY = Boolean.parseBoolean(props.getProperty("breeze.tts.dynamicPrimary", "false"));
            BREEZE_WARMUP_MIN_ENTRIES = Long.parseLong(props.getProperty("breeze.tts.warmupMinEntries", "50"));
            BREEZE_PRIME_OUTPUT = Boolean.parseBoolean(props.getProperty("breeze.tts.primeOutput", "false"));
            BREEZE_DAILY_CREDIT_LIMIT = Long.parseLong(props.getProperty("breeze.tts.dailyCreditLimit", "400"));

            AzureUsageTracker.setLimit(AZURE_MONTHLY_LIMIT_HOURS * 3600);
            AzureUsageTracker.setCharacterLimit(AZURE_MONTHLY_LIMIT_CHARACTERS);

            // --- AI ORCHESTRATION ---
            LLM_PERSONALITY_URL = props.getProperty("ciel.ai.personalityUrl", "http://localhost:11434/v1"); 
            LLM_PERSONALITY_MODEL = props.getProperty("ciel.ai.personalityModel", "gemma3:12b"); 
            
            LLM_EVALUATOR_URL = props.getProperty("ciel.ai.evaluatorUrl", "http://localhost:11434/v1"); 
            LLM_EVALUATOR_MODEL = props.getProperty("ciel.ai.evaluatorModel", "qwen3:8b");
            
            LLM_LOGIC_URL = props.getProperty("ciel.ai.logicUrl", "http://localhost:11434/v1");
            LLM_LOGIC_MODEL = props.getProperty("ciel.ai.logicModel", "deepseek-v3.1:671b-cloud");
            
            LLM_LOCAL_LOGIC_FALLBACK_URL = props.getProperty("ciel.ai.localLogicFallbackUrl", "http://localhost:1234/v1");
            LLM_LOCAL_LOGIC_FALLBACK_MODEL = props.getProperty("ciel.ai.localLogicFallbackModel", "phi-4-reasoning-plus");
            
            LLM_ONLINE_FALLBACK_URL = props.getProperty("ciel.ai.fallbackUrl", "https://api.openai.com/v1");
            LLM_ONLINE_FALLBACK_KEY = props.getProperty("ciel.ai.fallbackKey", "");
            AI_OBSERVER_ENABLED = Boolean.parseBoolean(props.getProperty("ciel.ai.observerEnabled", "true"));

            System.out.println("Ciel Debug: Settings loaded successfully.");

            profileLastModified = lastModified(resolveProfilePath());
            secretsLastModified = lastModified(externalSecrets);

        } catch (Exception e) {
            System.err.println("Ciel FATAL ERROR: Failed to parse settings.");
            throw new RuntimeException(e);
        }
    }

    /** Reload external profile/secrets changes without restarting the JVM. */
    public static boolean reloadIfChanged() {
        long now = System.currentTimeMillis();
        if (now - lastReloadCheck < 1000) return false;
        lastReloadCheck = now;
        Path profile = resolveProfilePath();
        Path secrets = resolveSecretsPath();
        long profileStamp = lastModified(profile);
        long secretsStamp = lastModified(secrets);
        if (profileStamp == profileLastModified && secretsStamp == secretsLastModified) return false;
        initialize();
        BreezeSpeechService.reloadConfiguration();
        System.out.println("Ciel Debug: External settings changed; live configuration reloaded.");
        return true;
    }

    private static Path resolveSecretsPath() {
        String configured = System.getenv("CIEL_SECRETS_PATH");
        return configured != null && !configured.isBlank() ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".cielcompanion", "secrets.properties");
    }

    private static Path resolveProfilePath() {
        String configured = System.getenv("CIEL_PROFILE_PATH");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        Path perUser = Path.of(System.getProperty("user.home"), ".cielcompanion", "profile.properties");
        if (Files.isRegularFile(perUser)) return perUser;
        return Path.of("config", "companion.profile.properties");
    }

    private static long lastModified(Path path) {
        try { return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0L; }
        catch (Exception ignored) { return -1L; }
    }

    private static boolean isSensitiveProperty(String key) {
        String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return lower.contains("secret") || lower.contains("password") || lower.contains("pass")
                || lower.endsWith(".key") || lower.contains("apikey") || lower.contains("api_key")
                || lower.contains("token");
    }

    public static String getVoiceNameHint() { return VOICE_NAME_HINT; }
    public static String getCompanionName() { return COMPANION_NAME; }
    public static String getCompanionPersona() { return COMPANION_PERSONA; }
    public static String getTtsPrimaryProvider() { return TTS_PRIMARY_PROVIDER; }
    public static String getTtsFallbackProvider() { return TTS_FALLBACK_PROVIDER; }
    public static String getTtsInstructions() { return TTS_INSTRUCTIONS; }
    public static String getVoiceLanguageCode() { return VOICE_LANGUAGE_CODE; }
    public static int getTtsRate() { return TTS_RATE; }
    public static int getTtsRateAnnoyed() { return TTS_RATE_ANNOYED; }
    public static long getCheckIntervalMs() { return CHECK_INTERVAL_MS; }
    public static boolean isVerboseLoggingEnabled() { return LOGGING_SHOW_VERBOSE_STATUS; }
    public static long getFirstGreetingDelaySeconds() { return FIRST_GREETING_DELAY_SECONDS; }
    public static long getLoginGreetingDelaySeconds() { return LOGIN_GREETING_DELAY_SECONDS; }
    public static long getUpdateStatusDelaySeconds() { return UPDATE_STATUS_DELAY_SECONDS; }
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
    public static String getDndKnowledgeMode() { return DND_KNOWLEDGE_MODE; }
    public static String getDndLiveStatePath() { return DND_LIVE_STATE_PATH; }
    public static String getAstronomyApiApplicationId() { return ASTRONOMY_API_APPLICATION_ID; }
    public static String getAstronomyApiApplicationSecret() { return ASTRONOMY_API_APPLICATION_SECRET; }
    public static String getIpGeolocationApiKey() { return IPGEOLOCATION_API_KEY; }
    public static String getAzureSpeechKey() { return AZURE_SPEECH_KEY; }
    public static String getAzureSpeechRegion() { return AZURE_SPEECH_REGION; }
    public static String getAzureVoiceName() { return AZURE_VOICE_NAME; }
    public static String getBreezeApiKey() { return BREEZE_API_KEY; }
    public static String getBreezeVoiceId() { return BREEZE_VOICE_ID; }
    public static String getBreezeModel() { return BREEZE_MODEL; }
    public static String getBreezeLanguageCode() { return BREEZE_LANGUAGE_CODE; }
    public static boolean isBreezeEnabled() { return BREEZE_ENABLED; }
    public static boolean isBreezePreferAll() { return BREEZE_PREFER_ALL; }
    public static boolean isBreezeCacheEnabled() { return BREEZE_CACHE_ENABLED; }
    public static boolean isBreezePrefetchEnabled() { return BREEZE_PREFETCH_ENABLED; }
    public static boolean isBreezeDynamicPrimary() { return BREEZE_DYNAMIC_PRIMARY; }
    public static long getBreezeWarmupMinEntries() { return BREEZE_WARMUP_MIN_ENTRIES; }
    public static boolean isBreezePrimeOutput() { return BREEZE_PRIME_OUTPUT; }
    public static long getBreezeDailyCreditLimit() { return BREEZE_DAILY_CREDIT_LIMIT; }

    public static String getLlmPersonalityUrl() { return LLM_PERSONALITY_URL; }
    public static String getLlmPersonalityModel() { return LLM_PERSONALITY_MODEL; }
    public static String getLlmEvaluatorUrl() { return LLM_EVALUATOR_URL; }
    public static String getLlmEvaluatorModel() { return LLM_EVALUATOR_MODEL; }
    public static String getLlmLogicUrl() { return LLM_LOGIC_URL; }
    public static String getLlmLogicModel() { return LLM_LOGIC_MODEL; }
    
    public static String getLlmLocalLogicFallbackUrl() { return LLM_LOCAL_LOGIC_FALLBACK_URL; }
    public static String getLlmLocalLogicFallbackModel() { return LLM_LOCAL_LOGIC_FALLBACK_MODEL; }
    
    public static String getLlmOnlineFallbackUrl() { return LLM_ONLINE_FALLBACK_URL; }
    public static String getLlmOnlineFallbackKey() { return LLM_ONLINE_FALLBACK_KEY; }
    public static boolean isAiObserverEnabled() { return AI_OBSERVER_ENABLED; }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }
}
