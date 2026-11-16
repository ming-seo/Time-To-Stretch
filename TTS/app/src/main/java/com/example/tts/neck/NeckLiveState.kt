package com.example.tts.neck

/** Latest values from [NeckSensingService] for the debug screen. Written and read on the main thread. */
object NeckLiveState {
    var running = false
    var screenInUse = false
    var currentTilt: Double? = null
    var lastCycle: CycleResult? = null
    var lastState: NeckState? = null
    var logDir: String? = null
}
