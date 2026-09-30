package com.example.talktoagent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;
import static org.junit.Assert.*;

/** Controlled executors and repository boundary; assertions observe only the public workflow. */
public class ConnectionPreparationTest {
    static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void next() { tasks.remove().run(); }
    }
    static class Store implements AuthorizationRepository {
        List<Endpoint> active = new ArrayList<>();
        Endpoint pending;
        boolean fail;
        Runnable duringRead;
        public byte[] phoneKey() { throw new UnsupportedOperationException(); }
        public byte[] sign(byte[] bytes) { throw new UnsupportedOperationException(); }
        public Endpoint pending() { return pending; }
        public List<Endpoint> active() {
            if (duringRead != null) duringRead.run();
            if (fail) throw new IllegalStateException("storage");
            return new ArrayList<>(active);
        }
        public void savePending(Endpoint value) { pending = value; }
        public void activate(Endpoint value, int epoch, String channels) { active = new ArrayList<>(List.of(value)); pending = null; }
        public void clearPending() { pending = null; }
    }
    static final class Listener implements ConnectionCoordinator.PreparationListener {
        int success, failure;
        public void prepared() { success++; }
        public void failed() { failure++; }
    }
    static AuthorizationRepository.Endpoint endpoint(int vector) throws Exception {
        Invitation invite = Invitation.parse(AuthorizationVectors.load().getAsJsonArray("valid").get(vector).getAsJsonObject().get("uri").getAsString());
        return new AuthorizationRepository.Endpoint(invite.channel, invite.target, invite.fingerprint);
    }
    @Test public void failedTargetUpdateKeepsNormalActiveAndAllowsExplicitPendingRecovery() throws Exception {
        Store store = new Store(); AuthorizationRepository.Endpoint active = endpoint(0);
        AuthorizationRepository.Endpoint update = new AuthorizationRepository.Endpoint("wifi", "wifi:192.168.1.21:8765", active.fingerprint);
        store.active.add(active); store.pending = update;
        ConnectionCoordinator flow = new ConnectionCoordinator(store);
        ConnectionCoordinator.Targets snapshot = flow.loadTargets();
        assertEquals(active.target, snapshot.normal("wifi").target);
        assertEquals(update.target, snapshot.pending("wifi").target);
        Queue worker = new Queue(), callbacks = new Queue(); Listener listener = new Listener();
        flow.prepareResume(snapshot.normal("wifi"), worker, callbacks, listener); worker.next(); callbacks.next();
        assertEquals(active.target, flow.selected().target);
        flow.disconnected();
        flow.prepareResume(snapshot.pending("wifi"), worker, callbacks, listener); worker.next(); callbacks.next();
        assertEquals(update.target, flow.selected().target); assertEquals(2, listener.success);
        assertEquals(active.target, flow.loadTargets().normal("wifi").target);
    }
    @Test public void normalConnectUsesPendingOnlyWhenChannelHasNoActive() throws Exception {
        Store store = new Store(); store.active.add(endpoint(1)); store.pending = endpoint(0);
        ConnectionCoordinator flow = new ConnectionCoordinator(store);
        assertEquals(store.pending.target, flow.loadTargets().normal("wifi").target);
        assertEquals("bt", flow.loadTargets().normal("bt").channel);
        assertNull(flow.loadTargets().pending("bt"));
    }
    @Test public void disconnectedOldSuccessCannotSelectOrStartNewAttempt() throws Exception {
        Store store = new Store(); store.active.add(endpoint(0)); store.active.add(endpoint(1));
        ConnectionCoordinator flow = new ConnectionCoordinator(store);
        Queue worker = new Queue(), callbacks = new Queue(); Listener old = new Listener(), current = new Listener();
        flow.prepareResume(endpoint(0), worker, callbacks, old);
        assertFalse(flow.canScan());
        flow.disconnected(); flow.prepareResume(endpoint(1), worker, callbacks, current);
        worker.next(); assertNull(flow.selected());
        worker.next(); callbacks.next();
        assertEquals(0, old.success + old.failure); assertEquals(1, current.success);
        assertEquals("bt", flow.selected().channel);
    }
    @Test public void queuedOldSuccessAndFailureAreIgnoredAfterDisconnectAndNewRequest() throws Exception {
        for (boolean failure : new boolean[]{false, true}) {
            Store store = new Store(); store.active.add(endpoint(0)); store.active.add(endpoint(1)); store.fail = failure;
            ConnectionCoordinator flow = new ConnectionCoordinator(store);
            Queue worker = new Queue(), callbacks = new Queue(); Listener old = new Listener(), current = new Listener();
            flow.prepareResume(endpoint(0), worker, callbacks, old); worker.next();
            flow.disconnected(); store.fail = false; flow.prepareResume(endpoint(1), worker, callbacks, current); worker.next();
            callbacks.next(); callbacks.next();
            assertEquals(0, old.success + old.failure); assertEquals(1, current.success);
            assertEquals("bt", flow.selected().channel);
        }
    }
    @Test public void newScanInvalidatesAlreadyQueuedResumeCallback() throws Exception {
        Store store = new Store(); store.active.add(endpoint(0));
        ConnectionCoordinator flow = new ConnectionCoordinator(store);
        Queue worker = new Queue(), callbacks = new Queue(); Listener old = new Listener();
        flow.prepareResume(endpoint(0), worker, callbacks, old); worker.next();
        flow.selectInvitation(AuthorizationVectors.load().getAsJsonArray("valid").get(1).getAsJsonObject().get("uri").getAsString());
        callbacks.next();
        assertEquals(0, old.success + old.failure); assertEquals("bt", flow.selected().channel);
    }
    @Test public void cancelledQueuedPreparationDoesNotLeaveEndpointSelected() throws Exception {
        Store store = new Store(); store.active.add(endpoint(0));
        ConnectionCoordinator flow = new ConnectionCoordinator(store);
        Queue worker = new Queue(), callbacks = new Queue(); Listener old = new Listener();
        flow.prepareResume(endpoint(0), worker, callbacks, old); worker.next(); flow.cancelPreparation(); callbacks.next();
        assertNull(flow.selected()); assertTrue(flow.canScan()); assertEquals(0, old.success + old.failure);
    }
    @Test public void destroyingOldUiOwnerCannotCancelServiceReconnectOnSharedCoordinator() throws Exception {
        Store store = new Store(); store.active.add(endpoint(0)); store.active.add(endpoint(1));
        ConnectionCoordinator flow = new ConnectionCoordinator(store);
        Queue worker = new Queue(), callbacks = new Queue(); Listener ui = new Listener(), service = new Listener();
        ConnectionCoordinator.Preparation uiRequest = flow.prepareResume(endpoint(0), worker, callbacks, ui);
        worker.next(); callbacks.next();
        flow.disconnected();
        flow.prepareResume(endpoint(1), worker, callbacks, service);
        uiRequest.cancel(); // The destroyed UI may still hold its completed request handle.
        worker.next(); callbacks.next();
        assertEquals(1, service.success); assertEquals(0, service.failure);
        assertEquals("bt", flow.selected().channel);
    }
    @Test public void uiOwnerCanCancelOnlyItsOwnPendingPreparation() throws Exception {
        Store store = new Store(); store.active.add(endpoint(0));
        ConnectionCoordinator flow = new ConnectionCoordinator(store);
        Queue worker = new Queue(), callbacks = new Queue(); Listener ui = new Listener();
        ConnectionCoordinator.Preparation request = flow.prepareResume(endpoint(0), worker, callbacks, ui);
        worker.next(); request.cancel(); callbacks.next();
        assertTrue(flow.canScan()); assertNull(flow.selected()); assertEquals(0, ui.success + ui.failure);
    }
    @Test public void cancellationIsNotBlockedByRepositoryReadAndCannotClaimAfterIt() throws Exception {
        Store store = new Store(); store.active.add(endpoint(0));
        ConnectionCoordinator flow = new ConnectionCoordinator(store);
        Queue worker = new Queue(), callbacks = new Queue(); Listener listener = new Listener();
        store.duringRead = () -> {
            Thread cancel = new Thread(flow::cancelPreparation); cancel.start();
            try { cancel.join(1000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse("Cancellation must not wait for storage I/O lock", cancel.isAlive());
        };
        flow.prepareResume(endpoint(0), worker, callbacks, listener); worker.next();
        assertTrue(flow.canScan()); assertNull(flow.selected()); assertTrue(callbacks.tasks.isEmpty());
        assertEquals(0, listener.success + listener.failure);
    }
}
