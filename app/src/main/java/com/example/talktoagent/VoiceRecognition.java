package com.example.talktoagent;

/** External recognition/capture boundary, not a second utterance coordinator. */
interface VoiceRecognition {
    void release();
    void finalizeAtDeadline();
    void close();
}
