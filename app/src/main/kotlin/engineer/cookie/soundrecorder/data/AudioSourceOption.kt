package engineer.cookie.soundrecorder.data

/**
 * Which microphone source to request from AudioRecord.
 *
 * AUTO prefers an unprocessed source when the device advertises one, then
 * falls back to VOICE_RECOGNITION and finally the plain microphone.
 */
enum class AudioSourceOption {
    AUTO,
    UNPROCESSED,
    VOICE_RECOGNITION,
    MIC,
}
