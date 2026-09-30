package com.example.talktoagent;
import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class InvitationTest {
    @Test public void sharedCanonicalInvitationsSelectExactTarget() throws Exception {
        for (JsonElement value : AuthorizationVectors.load().getAsJsonArray("valid")) {
            JsonObject vector = value.getAsJsonObject();
            Invitation invite = Invitation.parse(vector.get("uri").getAsString());
            assertEquals(vector.get("channel").getAsString(), invite.channel);
            assertEquals(vector.get("target").getAsString(), invite.target);
        }
    }
    @Test public void sharedInvalidInvitationsCannotSelectTarget() throws Exception {
        for (JsonElement value : AuthorizationVectors.load().getAsJsonArray("invalid"))
            assertThrows(IllegalArgumentException.class, () -> Invitation.parse(value.getAsString()));
    }
}
