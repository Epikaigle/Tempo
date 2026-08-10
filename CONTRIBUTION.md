# Contributing to Tempo

Thank you for contributing to Tempo. This guide outlines the contribution workflow and project principles.

## Project Principles

Tempo is built around three core principles:
- **Local-first**: Data remains on the device with no cloud server dependencies.
- **Privacy**: No user tracking, analytics, or external data collection.
- **Focused scope**: Changes prioritize stability and accuracy over feature density.

Changes should align with these principles.

## How to Contribute

### Workflow

1. Fork the repository.
2. Create a feature branch for your work.
3. Commit focused, atomic changes.
4. Submit a pull request to the `main` branch.

### Pull Request Guidelines

Include the following in your pull request description:
- Summary of the changes and why they were made.
- Relevant issue numbers.
- Screenshots or screen recordings for UI changes.
- Verification steps or test results.

Pull requests are evaluated on code quality, performance impact, and project scope. Maintainers will provide feedback if changes or alternative approaches are required.

### Types of Contributions

- **Bug Fixes**: Resolve crashes, UI rendering errors, or database migration bugs.
- **Feature Enhancements**: Improve existing workflows, accessibility, or error handling.
- **Documentation**: Clarify setup steps, code comments, or API references.
- **Localization**: Add translations or improve right-to-left layout support.

### Major Changes

Before starting work on large features, architecture refactors, or UI redesigns, open an issue to discuss the approach. This avoids unnecessary work on features that conflict with the project direction.

## UI & Design Guidelines

Tempo uses Jetpack Compose and Material 3 design components.

- **Encouraged**: Usability adjustments (spacing, touch targets, contrast), screen reader accessibility fixes, animation performance polish.
- **Requires prior discussion**: Visual identity updates (colors, fonts), structural navigation redesigns, or replacing established UI components.

## Code Quality Standards

- **Follow existing patterns**: Keep code consistent with existing MVVM, Clean Architecture, and Hilt setup.
- **Minimize external libraries**: Add dependencies only when necessary.
- **Write readable code**: Prefer clear function and variable names over complex inline logic.
- **Verify changes**: Confirm the project builds cleanly and existing functionality remains unbroken.

## Development Setup

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Architecture**: MVVM + Clean Architecture with Hilt
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36 (Android 16)

Run a local build before submitting:
```bash
./gradlew build
```

## Licensing

By contributing, you agree that your contributions are licensed under the project's custom modified AGPLv3 License. See [LICENSE](LICENSE) for full details.
