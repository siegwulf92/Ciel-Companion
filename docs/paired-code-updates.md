# Paired Ciel code baseline

Public Java: siegwulf92/Ciel-Companion. Private Python: siegwulf92/Ciel-Manas-Core.

This baseline reconciles independent desktop and laptop snapshots. The memory
bridge synchronizes saved memory, not source code or builds. Each release must
record both Java and Python commit IDs and preserve device-specific settings.

Credentials, finance data, databases, logs, models, caches, and machine settings
remain local. Trigger passphrases are loaded from `.local/trigger-keys.properties`;
existing installations must migrate those local values before activation.
Trigger sockets bind to loopback. No trigger keys are published here.

Build and validate in staging before touching live source or the running JAR.
Do not overwrite local edits, copy live SQLite databases, or choose source by
modification timestamps. Back up each runtime before deploying a paired release.

Validation: Java 21 Maven package build, ten Java regression programs, and 113
offline Python checks in the private companion candidate. Python code is excluded
from this public repository. Automatic deployment is a separate installation step.
