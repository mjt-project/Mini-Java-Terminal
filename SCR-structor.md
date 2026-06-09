## Project Structure

```text
mini-java-terminal/
├── src/
│   └── terminal/
│       ├── Main.java
│       │
│       ├── command/
│       │   ├── CommandCenter.java
│       │   └── CommandContext.java
│       │
│       ├── services/
│       │   ├── CloudflareDnsService.java
│       │   └── SftpServerService.java
│       │
│       └── system/
│           ├── ShellRunner.java
│           ├── PublicIpService.java
│           ├── LogService.java
│           ├── StateStore.java
│           ├── CommandGuard.java
│           └── RuntimeConfig.java
│
├── scripts/
│   └── auto-build.ps1
│
├── dist/
├── logs/
├── target/
├── pom.xml
├── README.md
└── .gitignore
```

### Folder Roles

```text
Main.java
→ Console display and application startup only.

command/
→ Routes user input to the correct feature or service.

services/
→ Feature-level services such as Cloudflare DDNS and SFTP.

system/
→ Core runtime utilities such as shell execution, logs, state storage, public IP checking, command blocking, and runtime configuration.

scripts/
→ Development helper scripts.

dist/
→ Optional output folder for copied release JAR files.

target/
→ Maven build output. This folder should not be committed.

logs/
→ Runtime logs. This folder should not be committed.
```
