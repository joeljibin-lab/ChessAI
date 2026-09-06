# ChessAI — Real-Time Android Chess Learning Assistant

ChessAI is a personal engineering passion project I created to explore a question I was interested in:

**Could an Android application observe a live digital chess game, maintain an understanding of the board state, and provide useful engine recommendations in real time?**

The result is an experimental Android prototype that combines screen capture, computer vision, chess-state tracking, Stockfish analysis, and an on-screen recommendation overlay.

> Project context: I created the concept, requirements, feature direction, testing process, and iterative design of this project. My prior programming experience was primarily basic Python, so I used AI coding assistants extensively to help implement and debug the Android/Kotlin code. I do not represent the entire codebase as being handwritten by me. This project represents my experience using engineering problem-solving, experimentation, testing, and AI-assisted development to turn an idea into a working prototype.

\---

## What It Does

ChessAI runs alongside a digital chess game on Android and attempts to:

* Capture the chessboard from the screen in real time.
* Detect changes to the board as moves are played.
* Maintain an internal virtual chessboard and FEN state.
* Validate visually detected moves against legal chess moves.
* Analyze the current position using Stockfish.
* Display the top engine recommendations directly over the board.
* Display possible opponent moves.
* Recover manually from a move missed by automatic detection.
* Handle board orientation when playing as either White or Black.

\---

## Current Testing Environment

The current prototype was developed and tested primarily with the **Chess.com browser interface on Android**.

For more consistent computer-vision input during development, I used a controlled visual configuration:

* Chess.com browser version on Android.
* Standard chessboard/piece appearance used consistently during testing.
* Move animation set to **Fast**.
* Previous-move square highlighting disabled.
* Consistent board orientation and layout during a game.

These settings reduce unnecessary visual changes between frames and make it easier for the computer-vision system to distinguish an actual chess move from interface animations or highlights.

The current version should therefore be considered a **prototype optimized for the environment in which it was developed and tested**, rather than a universal chess-interface recognition system.

### Example Testing Configuration
<img width="1536" height="1024" alt="chessai-demo" src="https://github.com/user-attachments/assets/3e92ccdb-e785-4aa9-b754-97e496b70b2b" />



---

## How It Works

At a high level, the application follows this pipeline:

```text
Android Screen Capture
        ↓
Chessboard Detection / Image Processing
        ↓
Visual Move Detection
        ↓
Legal-Move Validation
        ↓
Virtual Board + FEN State
        ↓
Stockfish Analysis
        ↓
On-Screen Recommendation Overlay
```

The application requires multiple stable observations before accepting a detected move. This helps reduce false detections caused by animations, board highlights, recommendation graphics, and other temporary visual changes.

Rather than relying entirely on pixel differences, detected visual changes are compared against the legal moves available from the application's current internal chess state.

## Technologies

* Kotlin
* Android SDK
* OpenCV
* Stockfish
* FEN and chess move-state tracking
* Android MediaProjection
* Android overlay services
* Gemini API for experimental board-recognition functionality
* AI-assisted software development

\---

## Engineering Challenges and Iterations

A large part of the project involved discovering problems through actual gameplay and then iteratively developing ways to address them.

### Recommendation Overlay Interference

One unusual problem was that the application analyzes the same screen on which it draws its recommendations.

The recommendation arrows and square outlines could therefore appear in captured frames and be interpreted by the computer-vision system as changes to the chessboard.

I experimented with masking and image-processing approaches to reduce this interference while keeping the recommendations visible to the user.

### Reliable Move Detection

Animations, captures, interface effects, and rapid moves can cause screenshots of the chessboard to differ significantly.

The application combines:

* Visual change detection
* Multiple stable observations
* Source/destination evidence
* Current piece occupancy
* Legal-move validation
* Internal board state

rather than accepting a move based only on raw pixel differences.

### Screen-Capture Stalls

During gameplay testing, I found that Android's screen-capture pipeline could occasionally stop delivering useful frames even though the capture session itself remained active.

I investigated these failures using Android Logcat and repeated gameplay tests.

The current prototype uses a lightweight capture-surface wake mechanism intended to restore frame delivery without rebuilding the entire chess state or restarting the application.

### Missed-Move Recovery

Automatic detection is not perfect.

I therefore added a manual **MISSED** recovery feature.

When a move is missed, the user can identify its destination square. The application checks the legal moves from its current virtual board and automatically infers the originating piece when the destination uniquely identifies a legal move.

If multiple pieces could legally reach that square, the user can additionally identify the source square.

After recovery, the application establishes a fresh visual baseline and continues tracking the game.

\---

## Development and Testing Approach

This project was developed iteratively through repeated gameplay tests:

1. Define a desired behavior or identify a failure.
2. Implement or modify a detection strategy.
3. Test it during actual chess games.
4. Inspect Android Logcat output and observed behavior.
5. Identify the cause of failures.
6. Evaluate possible solutions.
7. Modify the implementation.
8. Test again.

This iterative process was particularly important because many problems only became apparent when screen capture, computer vision, Android overlays, chess logic, and the external chess interface were all operating simultaneously.

\---

## AI-Assisted Development

AI coding assistants were used extensively throughout this project to generate, modify, explain, and debug code.

My role focused on:

* Originating the project idea
* Defining the application's requirements
* Deciding desired behavior and features
* Evaluating proposed approaches
* Testing implementations
* Identifying failures during real gameplay
* Interpreting application behavior and diagnostic logs
* Making design and tradeoff decisions
* Directing subsequent iterations

My programming experience before this project was primarily basic Python. I would not claim that I could independently reproduce the entire Android/Kotlin codebase from scratch.

Instead, this project represents my experience using modern development tools, technical experimentation, and engineering problem-solving to take an original idea significantly beyond my previous software experience and develop it into a working prototype.

\---

## Current Limitations

ChessAI is an experimental prototype, not production chess software.

Current limitations include:

* The computer-vision system has been developed around a specific visual chess interface and configuration.
* Rapid visual changes or interrupted screen capture can occasionally cause a move to be missed.
* Manual recovery may still be required in some games.
* Different board themes, piece sets, animations, highlights, screen sizes, or interface layouts may require additional calibration or development.
* Some experimental functionality remains in the codebase as the project continues to evolve.

These limitations are also useful areas for future development.

\---

## Project Status

ChessAI is an ongoing personal learning and engineering project.

My goal is not to present it as a finished commercial application, but to use it to explore computer vision, Android development, chess engines, state tracking, debugging, and AI-assisted software development.

\---

## Third-Party Software and References



## Stockfish

### 

### ChessAI uses the Stockfish chess engine for position analysis and move recommendations.

### 

### Stockfish is free software licensed under the GNU General Public License version 3 (GPL v3).

### 

### Official project:

### https://github.com/official-stockfish/Stockfish

### 

### The compiled Stockfish binaries are not included in this repository because they exceed GitHub's individual file-size limit.

### 

### To run the project, provide a Stockfish binary compiled for Android and place it in the appropriate ABI directory:

### 

app/src/main/jniLibs/arm64-v8a/libstockfish.so



## Chess-Tracker

Early experimentation for this project was informed by the **Chess-Tracker** project by yaseralie:

https://github.com/yaseralie/Chess-Tracker

That project provided an early reference while I was exploring chessboard tracking using computer vision.

ChessAI subsequently developed into an Android/Kotlin implementation incorporating Android screen capture, on-screen overlays, virtual board-state tracking, legal-move validation, Stockfish integration, and additional move-detection and recovery systems.

See `THIRD_PARTY_NOTICES.md` for attribution information.

\---

## Screenshots and Demo

Screenshots and a demonstration of the application will be added here.

Planned examples include:

* Chess.com board configuration used during testing.
* ChessAI detecting a live game.
* Top-three Stockfish recommendation arrows.
* Opponent legal-move visualization.
* Manual missed-move recovery.
* Example gameplay sequence.

\---

## Author

**Joel Jibin**

Applied Mathematics, Engineering, and Physics (AMEP) — Mechanical Engineering  
University of Wisconsin–Madison

