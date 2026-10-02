# Grooverider: rules for Claude
- Two Claude Code sessions share this folder: VS Code (web app) and Android Studio (Android app). Only one works at a time.
- Before any task: check the branch and that the tree is clean. If not on main or not clean, stop and ask Wren.
- Web lane: docs/, webtests/. Android lane: app/, nativetest/, Gradle files. Stay in your lane.
- core/ is shared by both apps. Ask Wren before changing it.
- Work on a branch. Ask Wren before merging to main (merging to main publishes the web app). Don't push without asking.
- Never commit gradle/libs.versions.toml version bumps unless asked.
- Android plan: ANDROID_OBSERVATORY_BRIEF.md. Full spec: GROOVERIDER_SPEC.md.
- Wren is learning. Explain git and build steps in plain language.
