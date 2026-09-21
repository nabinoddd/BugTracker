# Bug Tracker

CS 4405 Mobile Applications — Unit 3 implementation.

An Android issue tracker with offline CRUD in Room, Retrofit synchronization,
persistent drafts, background retries, and explicit conflict resolution.

## Implementation and verification

[Project details and execution specification](PROJECT.txt) describe the backend,
Android build, API contract, and test scope.

- [Android build and 11 passing tests](evidence/android-tests.txt)
- [Six passing backend tests](evidence/backend-tests.txt)
- [Development history](https://github.com/nabinoddd/BugTracker/commits/main/)
- [Feature and hotfix branches](https://github.com/nabinoddd/BugTracker/branches)
- [Version tags](https://github.com/nabinoddd/BugTracker/tags)

## Git workflow

The persistence and networking changes were developed on `feature/room-retrofit`.
Draft recovery and background work were developed on `feature/lifecycle-retry`.
The `hotfix/prevent-duplicate-submissions` branch corrects repeated submission taps.
Merge commits retain the branch history. Annotated tags `v1.0` and `v1.1` identify
the baseline and corrected implementation snapshots.

The project builds a debug APK. Automated tests use Robolectric, real Room,
MockWebServer, and the SQLite backend. No physical-device or emulator UI test
has been performed. The backend is a local classroom service without production
authentication.
