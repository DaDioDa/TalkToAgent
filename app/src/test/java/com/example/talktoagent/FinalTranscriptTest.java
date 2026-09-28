package com.example.talktoagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FinalTranscriptTest {
    @Test
    public void finalBeforeReleaseNeedsNoSecondFinalToDeliver() {
        FinalTranscript transcript = new FinalTranscript();
        transcript.addFinal("甲", 100);
        assertNull(transcript.takeIfSettled(10000));
        transcript.release(500);
        transcript.streamEnded(600);
        assertNull(transcript.takeIfSettled(1599));
        assertEquals("甲", transcript.takeIfSettled(1600));
        assertNull(transcript.takeIfSettled(3000));
    }

    @Test
    public void allFinalSegmentsAfterReleaseAreJoinedBeforeDelivery() {
        FinalTranscript transcript = new FinalTranscript();
        transcript.addFinal("前段", 100);
        transcript.release(200);
        transcript.streamEnded(250);
        transcript.addFinal("甲", 300);
        assertNull(transcript.takeIfSettled(1250));
        transcript.addFinal("乙", 1290);
        assertNull(transcript.takeIfSettled(2289));
        assertEquals("前段甲乙", transcript.takeIfSettled(2290));
        transcript.addFinal("過晚", 2400);
        assertNull(transcript.takeIfSettled(4000));
    }

    @Test
    public void emptyFinalDoesNotResetQuietWindowAndDeadlineCanDeliver() {
        FinalTranscript transcript = new FinalTranscript();
        transcript.release(0);
        transcript.streamEnded(100);
        transcript.addFinal("已定稿", 7000);
        assertFalse(transcript.addFinal("", 7500));
        assertNull(transcript.takeIfSettled(7999));
        assertEquals("已定稿", transcript.takeIfSettled(8000));
    }

    @Test
    public void interimOnlyOrNoFinalNeverDelivers() {
        FinalTranscript transcript = new FinalTranscript();
        transcript.release(100);
        transcript.streamEnded(200);
        transcript.addFinal("", 300);
        assertNull(transcript.takeIfSettled(9000));
    }
}
