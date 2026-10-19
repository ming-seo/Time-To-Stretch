# Time To Stretch

**Context-aware stretching prompts with movement verification**

CSED404 Mobile & Ubiquitous Computing · Term Project (Fall 2026)
Team: Park Hanbi, Cho Minseo

---

## Overview

Holding one posture for a long time, especially looking down at a phone, makes the neck, shoulders and back stiff. Stretching relieves this stiffness, but only when it is done regularly, and existing break reminders rely on fixed timers that ignore the user's actual state and never check whether a stretch was done.

**Time To Stretch** is an Android app that:

- estimates accumulated physical strain from phone sensors,
- chooses the right moment and a stretch that matches the cause of strain, and
- updates future recommendations from completion evidence and user feedback.

## How it works

```
1. Sensing  →  2. Estimation  →  3. Intervention
                                        ↓
6. Adaptive  ←  5. User feedback  ←  4. Verification
   recommendation
```

| Step | What it does |
| --- | --- |
| **Sensing** | Tracks inactivity (Activity Recognition, step count) and phone-use exposure (screen state, device tilt). Device tilt is a proxy, not a direct neck-angle measurement. |
| **Estimation** | Maintains two scores, a neck-related debt `D_n` and a sedentary debt `D_s`, combined as `D = w_n × D_n + w_s × D_s`. |
| **Intervention** | Prompts when `D` exceeds a calibrated threshold, deferring during movement, calls or class hours, with a cooldown. The dominant score selects neck/shoulder stretches or walking/lower-body movements. |
| **Verification** | Camera mode (CameraX + ML Kit pose landmarks), camera-free walking (steps + motion), or manual confirmation stored as self-reported. |
| **User feedback** | Difficulty (easy / manageable / difficult) and helpfulness (helpful / neutral / not helpful). |
| **Adaptive recommendation** | Rule-based ranking from completion and feedback in the MVP; ML is explored only after enough reliable data. |

## Roadmap

| Period (2026) | Development milestone | Evaluation / deliverable |
| --- | --- | --- |
| Late Oct – early Nov | Sensor logging + activity monitoring | Check phone placement, missing data and battery cost |
| Mid – late Nov | Debt scores + notifications, basic camera / walking verification | Working midterm prototype; test verification against labels |
| Early – mid Dec | Adaptive ranking + integrated app | Small pilot vs. fixed-timer reminders (completion, burden, relevance) |
| Late Dec | Analyze results + refine prototype | Final demonstration and report |

## Documents

- [Proposal (Oct 2026)](docs/proposal/Proposal.pdf)

## Repository structure

```
Time-To-Stretch/
├── README.md
└── docs/
    └── proposal/
        └── Proposal.pdf
```

Android app source will be added under `app/` as development begins.
