# Java Panel Terminal Lab

> A small Java-based command bridge for educational sandbox, runtime, and server-panel behavior testing.

## Overview

Java Panel Terminal Lab is a simple Java console application designed for learning how command execution, process handling, working directories, logs, and server-panel consoles behave in controlled environments.

The project was created for educational and authorized testing purposes only. It is intended to help developers, students, and hosting providers understand how a Java process can interact with the underlying operating system through standard input, output, and `ProcessBuilder`.

This repository does not provide tools for bypassing permissions, escaping sandboxes, abusing shared hosting, mining cryptocurrency, or attacking infrastructure.

## Features

* Interactive command input from a Java console
* Cross-platform command execution using:

  * `cmd.exe /c` on Windows
  * `bash -lc` on Linux/macOS
* Current working directory tracking with `cd` and `pwd`
* Automatic log creation in the `logs/` directory
* ANSI-colored console messages
* Basic command blocking for commands that are commonly unsafe or unsuitable for web panel consoles
* Optional command timeout behavior

  * `0` means no timeout
  * positive values limit command runtime in seconds

## Educational Use Cases

This project can be used to study:

* How Java communicates with system processes
* How `ProcessBuilder` starts shell commands
* How server panels pass input into a running Java application
* How command output can be captured and logged
* How working directory state can be managed inside a long-running Java process
* Why sandboxing, process limits, and permission boundaries matter in hosting environments

## What This Project Is Not

This project is not:

* A hacking tool
* A privilege escalation tool
* A sandbox escape
* A cryptocurrency miner
* A botnet component
* A malware loader
* A bypass tool for hosting restrictions
* A replacement for a real VPS or container platform

Do not use this project to violate the rules of any hosting provider, school system, company infrastructure, or third-party server.

## Responsible Use Notice

Only run this project in environments where you have explicit permission.

You are responsible for how you use this code. Running arbitrary system commands can be dangerous. A command bridge can read, modify, or delete files depending on the permissions of the user account running the Java process.

Recommended environments:

* Local development machine
* Personal VPS
* Private lab server
* Authorized testing environment
* Hosting sandbox where testing has been approved

Not recommended:

* Public shared hosting without permission
* School or company systems without approval
* Third-party servers
* Production environments

## Safety Notes

The application contains basic command blocking for commands such as:

* `su`
* `sudo`
* `apt install`
* `apt-get install`
* `nano`
* `vim`
* `vi`
* `top`
* `htop`

These blocks are basic guardrails only. They are not a security sandbox. Real security should be enforced by the operating system, container runtime, hosting panel, and resource control policies.

## Requirements

* Java 11 or newer
* A terminal or server console that supports standard input and output

## Build

Compile the Java file:

```bash
javac Test.java
```

Create a JAR file:

```bash
echo "Main-Class: Test" > manifest.txt
jar cvfm server.jar manifest.txt Test.class
```

Run:

```bash
java -jar server.jar
```

## Basic Commands

Inside the Java console:

```text
help
pwd
cd <folder>
ls
java -version
shutdown-terminal
```

Example:

```text
cd logs
pwd
ls
```

## Timeout Configuration

The command timeout is controlled by:

```java
private static int COMMAND_TIMEOUT_SECONDS = 0;
```

Behavior:

```text
0  = no timeout
60 = stop command after 60 seconds
300 = stop command after 5 minutes
```

Use a positive timeout for safer testing. Use `0` only in a controlled environment where long-running commands are expected.

## Logs

Logs are automatically created in:

```text
logs/
```

Each session creates a log file with a timestamped filename.

The log may contain command input and command output. Do not use this project to process secrets, passwords, private tokens, API keys, or sensitive data.

## Security Considerations

This project demonstrates why unrestricted command execution should be treated carefully.

If you are a hosting provider, server-panel developer, or sandbox designer, consider enforcing:

* Per-process CPU limits
* RAM limits
* Disk quota limits
* Process count limits
* Network access policies
* Child process tracking
* Automatic cleanup when the main server process stops
* Separation between game commands and host shell commands
* Clear user-facing policy for allowed and disallowed runtime behavior

## Repository Scope

This repository only contains the Java source code and documentation.

It should not include:

* Prebuilt third-party binaries
* Operating system images
* Firmware blobs
* Disk images
* Credentials
* Private server files
* Hosting provider files

If additional third-party tools are used during personal experiments, users must obtain them from their official sources and comply with their respective licenses.

## License

This project is licensed under the GNU General Public License v3.0.

Add a `LICENSE` file containing the full GPL-3.0 license text.

Suggested SPDX identifier for source files:

```text
SPDX-License-Identifier: GPL-3.0-or-later
```

## Disclaimer

This project is provided for educational and research purposes only.

The author is not responsible for misuse, damage, data loss, account suspension, service disruption, or policy violations caused by improper use of this project.

Use responsibly, only with permission, and only in environments where you are authorized to test.
