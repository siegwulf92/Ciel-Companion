Ciel Companion
Welcome to Ciel Companion, a proactive, ambient AI assistant designed to bring your desktop to life. Ciel observes your activity, keeps you company during idle moments, and provides timely, context-aware information, all with a unique personality.

How Ciel Works: Core Features
Ciel is built on a modular system that allows her to be aware of you and your digital environment.

1. Idle Phase & Mood System
Ciel is most active when you are not. She tracks your keyboard and mouse input to determine how long you've been idle. As time passes, she transitions through several phases, and her mood (and the color of her orb) changes to reflect this:

Phase 0 (Active): When you are actively using the computer, Ciel remains silent and observant. Her mood is Focused (Gold).

Phase 1 (Content): After a few minutes of inactivity, Ciel enters a calm state, occasionally sharing ambient information or observations. Her mood is Content (Green).

Phase 2 (Restless): As more time passes, she becomes a bit restless, and her dialogue becomes more direct. Her mood is Restless (Yellow).

Phase 3 (Impatient): Ciel becomes notably impatient, questioning your absence. Her mood is Impatient (Orange).

Phase 4 (Annoyed): After a long period of inactivity, Ciel becomes annoyed, delivering a monologue and initiating a security log-off warning. Her mood is Annoyed (Red).

2. Application Awareness
Ciel can recognize when you launch specific applications, especially games. She does this using a robust, two-tiered system:

Process Name (.exe): She first tries to identify an application by its unique executable name.

Window Title (Fallback): If the executable is generic (e.g., launcher.exe), she intelligently scans the active window's title for the game's friendly name. This system is designed to ignore web browsers to prevent false positives from websites like YouTube.

3. Astronomical Awareness
Ciel keeps an eye on the sky for you. This system is designed to be efficient, using a once-per-day cached API call to minimize network usage.

Daily Report: The first time she enters an idle phase each day, she delivers a sequential report of major events: upcoming eclipses (and whether they're visible in your region), and the day's sunrise/sunset times.

Ambient Chatter: Other celestial information—like the current moon phase, prominent constellations, visible planets, and active meteor showers—is added to her random dialogue pool for a more dynamic feel.

Customization Guide
You can tailor almost every aspect of Ciel's behavior by editing her configuration files.

ciel_settings.properties
This is the main file for controlling her core logic.

ciel.firstGreetingDelaySeconds: How long she waits after startup to give her first greeting.

ciel.phase1ThresholdMin, ciel.phase2ThresholdMin, etc.: The number of idle minutes required to trigger each phase.

ciel.phase1MinGapSec, ciel.phase1MaxGapSec, etc.: The random time range between her speeches in each phase.

ciel.browserProcessesRegex: A list of browser executables to ignore for game detection.

gui_settings.properties
This file controls her visual appearance.

gui.size: The size of her orb in pixels.

mood.focused.color.r, mood.impatient.color.g, etc.: Change the RGB values (0.0 to 1.0) to customize the color for each of her moods.

behavior.movement.enabled: Set to false to stop her from floating around the screen.

astronomy.properties
Configure her connection to the outside world.

location.us.zip: Set this to your ZIP code for accurate astronomical data.

api.ipgeolocation.key: Required. Your free API key from ipgeolocation.io for moon phase data.

show.eclipses, show.moonPhase, etc.: Set any of these to false to disable specific announcements.

app_profiles.properties
Teach Ciel to recognize your applications.

# For a game with a unique .exe
ff7remake_.exe.name=Final Fantasy VII Remake
ff7remake_.exe.category=Game

# For a game with a generic launcher
launcher.exe_witcher3.name=The Witcher 3: Wild Hunt
launcher.exe_witcher3.category=Game
launcher.exe_witcher3.windowTitleRegex=(?i)The Witcher 3

ciel_lines_ja.properties
This file contains all of Ciel's dialogue. You can add, remove, or edit any line to customize her personality.

Roadmap & Future Development
Ciel is a constantly evolving project. The goal is to move beyond a scripted assistant and create a true AI companion. Here are the key areas for future improvement:

True Conversational Ability: The next major step is to integrate a Large Language Model (LLM). This will allow Ciel to move beyond her pre-written scripts and generate dynamic, unique responses, enabling you to have genuine conversations with her.

Expanded Memory: To support true conversation, her memory systems need to be enhanced. This includes improving her short-term memory (to track the context of a conversation) and her long-term memory (to recall facts and previous interactions more naturally).

PhonoKana Dictionary (PhonoKana.java): Her pronunciation engine is powerful but relies on a dictionary of known words. This dictionary needs to be continuously expanded to ensure she can correctly pronounce new words and names that come up in conversation.

Advanced Voice Commands: The current voice command system is basic. The goal is to expand it to handle more complex, multi-part commands and integrate it with her conversational abilities, allowing for a more natural, hands-free way to interact with your PC.