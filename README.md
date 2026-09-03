# AutoMate

A free, open-source Android automation app that lets you define **Trigger → Condition → Action** rules to automate any app on your phone.

## Features

- **Geofencing Triggers**: Auto-activate tasks when you enter or leave a location
- **UI Automation**: Click buttons, type text, and interact with any app
- **Multi-Account Support**: Manage multiple accounts for different apps
- **Smart Popup Handling**: Automatically dismiss errors and handle popups
- **Pre-built Profiles**: Ready-to-use automation for Beehive HRMS and WhatsApp
- **Custom Tasks**: Create your own automation rules

## Use Cases

### Beehive HRMS Attendance
- **Auto Time-In**: Automatically check in when you arrive at the office
- **Auto Time-Out**: Automatically check out when you leave
- **Smart Popup Handling**: Dismiss location errors and retry
- **Morning Prompt**: Daily reminder asking if you're going to work

### WhatsApp
- **Auto-Reply**: Send predefined messages based on location
- **Scheduled Messages**: Send messages at specific times

### Metro Tickets
- **Auto-Book**: Book tickets when you arrive at the station

## Setup

### Prerequisites
- Android 8.0 (API 26) or higher
- Location permissions
- Accessibility Service enabled

### Installation

1. Download the APK from [GitHub Releases](https://github.com/your-username/AutoMate/releases)
2. Install the APK on your Android device
3. Open AutoMate and follow the setup wizard
4. Enable Accessibility Service when prompted
5. Grant location permissions
6. Configure your office location and work hours

### Accessibility Service

AutoMate requires Accessibility Service to interact with other apps on your behalf. This service:
- Reads screen content to find buttons and fields
- Performs taps and text input when triggered
- Only operates when explicitly triggered by your automation rules
- Never sends data to external servers

## Architecture

```
AutoMate/
├── engine/                    # Core automation engine
│   ├── AccessibilityDriver    # UI element finder + actor
│   ├── ActionExecutor         # Runs action sequences
│   ├── TriggerManager         # Geofence, Time, Manual triggers
│   ├── VariableStore          # Global variables
│   └── TaskRunner             # Orchestrates Trigger→Condition→Action
├── profiles/                  # App-specific profiles
│   ├── beehive/               # Beehive HRMS automation
│   └── whatsapp/              # WhatsApp automation
├── data/                      # Room database
├── domain/                    # Models and business logic
└── ui/                        # Jetpack Compose UI
```

## Development

### Build

```bash
# Clone the repository
git clone https://github.com/your-username/AutoMate.git

# Open in Android Studio or build from command line
./gradlew assembleDebug
```

### Tech Stack
- Kotlin
- Jetpack Compose + Material 3
- Hilt (Dependency Injection)
- Room (Database)
- Google Play Services Location (Geofencing)
- GitHub Actions (CI/CD)

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Disclaimer

This app is for educational and personal use only. Use responsibly and in accordance with the terms of service of the apps you automate.
