package com.example.tts.neck

/** Latest values from [NeckSensingService] for the debug screen. Written and read on the main thread. */
object NeckLiveState {
    var running = false
    var screenInUse = false
    var currentTilt: Double? = null
    var lastCycle: CycleResult? = null
    var lastState: NeckState? = null
    var logDir: String? = null
    var lastSample: FaceSample? = null
    /** The mode B collection screen holds the front camera; the service skips its camera bursts meanwhile. */
    var collectScreenOpen = false
}
