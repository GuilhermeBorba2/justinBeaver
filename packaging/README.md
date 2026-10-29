# Installers

`build.sh` produces a desktop app with an Electron window and a bundled Java runtime (nothing else to install):

| System | File | Install |
|---|---|---|
| Ubuntu / Debian | `dist/justinbeaver_<version>_amd64.deb` | `sudo apt install ./justinbeaver_<version>_amd64.deb`, or double-click it in the Software center. JustinBeaver appears in the applications menu. |
| Windows 10/11 (64-bit) | `dist/JustinBeaver-Setup-<version>.exe` | Double-click. It installs for the current user (no administrator prompt) with Start menu and desktop shortcuts, and an uninstaller in *Apps & features*. |

Opening JustinBeaver starts its Java backend and opens a dedicated Electron window. Closing the window quits both
processes (or use the account menu → *Quit*). Running it again focuses the existing window.
Only Google sign-in opens the system browser, as required by Google's desktop OAuth flow; the app waits for its callback.
Your connections, scripts and settings live in
`~/.justinbeaver` (`%USERPROFILE%\.justinbeaver` on Windows) and are kept when you uninstall; the log is `logs/justinbeaver.log` there.
JDBC drivers are downloaded the first time a database type is used, so the first connection needs internet.

## Building

```bash
packaging/build.sh            # both
packaging/build.sh linux      # only the .deb
packaging/build.sh windows    # only the .exe
```

Run it on Linux with a JDK 21 (`JAVA21_HOME`, or one under `~/.sdkman`; otherwise Temurin 21 is downloaded), Maven, Node,
`dpkg-deb`. Node must satisfy the versions specified by the desktop dependencies. The Windows installer is made from Linux too:
Electron Packager downloads the Windows Electron runtime, the Windows Java runtime is linked from the Windows JDK's
modules (downloaded once into `.cache/`) and NSIS builds the setup (taken from `apt` without root if `makensis` is not installed).
The Linux package includes Chromium's sandbox helper with root ownership and mode 4755. Install the `.deb` normally so the
sandbox is configured; launching an extracted package may need user namespaces enabled on the host. The renderer has no Node
access and communicates with the main process only through a narrow Google sign-in bridge.

## Running the desktop app from source

```bash
JAVA_HOME=/path/to/jdk-21 mvn package -DskipTests
mkdir -p packaging/.work/app
cp target/justinbeaver-0.1.0-SNAPSHOT.jar packaging/.work/app/justinbeaver.jar
cd desktop
npm ci
JAVA_HOME=/path/to/jdk-21 npm start
```

`npm test` in `desktop` checks navigation and external-link rules. After preparing the JAR as above,
`JAVA_HOME=/path/to/jdk-21 npm run test:backend` tests a real Java startup, API readiness and clean shutdown on parent-pipe EOF.
The frontend's browser dev server remains available for UI development; installed launchers use Electron.

## Sign in with Google

Configure the app's Google OAuth client when building installers. Users press *Continue with Google*, choose their
account on Google's own page, and return to JustinBeaver. They never need a Google Cloud project or OAuth credentials.

To import a downloaded **Desktop app** JSON once for local installer builds:

```bash
python3 packaging/import-google-client.py /path/to/client_secret.json
packaging/build.sh
```

The imported configuration is stored in `packaging/.google-client.json` with owner-only permissions and ignored by Git.
Environment variables override it when provided:

```bash
JB_GOOGLE_CLIENT_ID=123-abc.apps.googleusercontent.com JB_GOOGLE_CLIENT_SECRET=GOCSPX-... packaging/build.sh
```

Without a client, the sign-in screen explains that Google sign-in is unavailable in this build; the app still works locally.
Create the client once for JustinBeaver, following Google's [desktop OAuth guide](https://developers.google.com/identity/protocols/oauth2/native-app):

1. Create a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable **Google Drive API** under *APIs & Services → Library*.
3. Configure the app's branding and audience in **Google Auth Platform**, with the name **JustinBeaver** and your support email.
   For testing with personal Google accounts, select an external audience and add the accounts under *Test users*.
4. Configure data access for `openid`, `email`, `profile` and `https://www.googleapis.com/auth/drive.appdata`.
5. Under *Clients*, create an OAuth client with application type **Desktop app**. Copy its client ID and client secret into
   `JB_GOOGLE_CLIENT_ID` and `JB_GOOGLE_CLIENT_SECRET` in your build environment. Do not commit downloaded credentials.
6. Test sign-in with an allowed account before distribution. Review Google's publishing requirements in the console before
   making the app available beyond test users.

For local development, the backend reads the same environment variables:

```bash
export JB_GOOGLE_CLIENT_ID='YOUR_CLIENT_ID.apps.googleusercontent.com'
export JB_GOOGLE_CLIENT_SECRET='YOUR_DESKTOP_CLIENT_SECRET'
mvn spring-boot:run
```

The backend uses authorization code with PKCE and a loopback callback (`http://127.0.0.1:<port>/api/google/callback`).
The client must be a **Desktop app** client so it supports the local port used by the installed app. Existing locally saved
clients remain supported for compatibility. No client ID or secret is sent to the frontend configuration screen.

What is synchronized: connections (with their saved passwords and SSH secrets), saved scripts and preferences/templates.
It is encrypted on the computer (AES-256-GCM, key from a passphrase the user picks, PBKDF2) before it goes to the hidden Drive
folder, so Google never sees passwords. Every computer asks for the same passphrase once.
