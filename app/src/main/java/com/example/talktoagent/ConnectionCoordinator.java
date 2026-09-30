package com.example.talktoagent;

import java.security.SecureRandom;
import java.util.Map;

/** Public scan → durable authorization → correlated delivery workflow, independent of Android I/O. */
public final class ConnectionCoordinator {
    public enum Event { PROOF, AUTHORIZED, PASTED, REJECTED }
    public static final class Result {
        public final Event event;
        public final String outbound, id, code;
        Result(Event event, String outbound, String id, String code) { this.event = event; this.outbound = outbound; this.id = id; this.code = code; }
    }
    private final AuthorizationRepository repository;
    private final SecureRandom random;
    private AuthorizationRepository.Endpoint selected;
    private Invitation invitation;
    private AuthorizationProtocol.Session session;
    private boolean attempting, ready, utterance, outcomeUnknown;
    private String pendingId;
    private final java.util.Set<String> sentIds = new java.util.HashSet<>();
    private long preparationGeneration;
    private boolean preparing, waitingForBond;
    public enum BondState { NONE, BONDING, BONDED }
    public enum BondProgress { IGNORED, WAITING, CONNECT, CANCELLED }
    public synchronized void waitForSystemBond() {
        if (!idle() || preparing || selected == null || !selected.channel.equals("bt"))
            throw new IllegalStateException("No idle Bluetooth selection");
        cancelPreparation(); waitingForBond = true;
    }
    public synchronized boolean waitingForSystemBond() { return waitingForBond; }
    /** UI adapters must query the real specified device, not trust a broadcast's claimed state. */
    public synchronized BondProgress observeSystemBond(String target, BondState state) {
        if (!waitingForBond || selected == null || !selected.target.equals(target)) return BondProgress.IGNORED;
        if (state == BondState.BONDING) return BondProgress.WAITING;
        waitingForBond = false;
        if (state == BondState.BONDED) return BondProgress.CONNECT;
        cancelPreparation(); invitation = null; selected = null; return BondProgress.CANCELLED;
    }
    public interface PreparationListener {
        void prepared();
        void failed();
    }
    /** Owner-scoped cancellation: an old UI handle cannot cancel a newer service request. */
    public static final class Preparation {
        private final ConnectionCoordinator owner;
        private final long generation;
        private Preparation(ConnectionCoordinator owner, long generation) {
            this.owner = owner; this.generation = generation;
        }
        public void cancel() {
            synchronized (owner) {
                if (owner.preparing && owner.preparationGeneration == generation) owner.cancelPreparation();
            }
        }
    }
    /** Cancels only unfinished preparation, never an established transport/service session. */
    public synchronized void cancelPreparation() { preparationGeneration++; preparing = false; }
    private boolean idle() { return !attempting && !ready && !utterance && !waitingForBond && pendingId == null; }
    private synchronized long requestPreparation() {
        if (!idle()) throw new IllegalStateException("Connection busy");
        preparing = true; return ++preparationGeneration;
    }
    public static final class Targets {
        public final java.util.List<AuthorizationRepository.Endpoint> active;
        public final AuthorizationRepository.Endpoint pending;
        Targets(java.util.List<AuthorizationRepository.Endpoint> active, AuthorizationRepository.Endpoint pending) {
            this.active = java.util.List.copyOf(active); this.pending = pending;
        }
        /** Normal connect never lets a failed update obscure an existing active target. */
        public AuthorizationRepository.Endpoint normal(String channel) {
            for (AuthorizationRepository.Endpoint endpoint : active) if (endpoint.channel.equals(channel)) return endpoint;
            return pending(channel);
        }
        public AuthorizationRepository.Endpoint pending(String channel) {
            return pending != null && pending.channel.equals(channel) ? pending : null;
        }
    }
    /** Shared snapshot loader; call on an I/O executor, not the UI thread. */
    public Targets loadTargets() throws Exception { return new Targets(repository.active(), repository.pending()); }
    private boolean known(AuthorizationRepository.Endpoint endpoint) throws Exception {
        Targets targets = loadTargets();
        boolean known = same(endpoint, targets.pending);
        for (AuthorizationRepository.Endpoint active : targets.active) known |= same(endpoint, active);
        return known;
    }
    /** Repository reads happen on the worker without holding the workflow monitor. Both
     * claiming the selection and dispatching its callback independently reject stale requests.
     */
    public Preparation prepareResume(AuthorizationRepository.Endpoint endpoint, java.util.concurrent.Executor worker,
            java.util.concurrent.Executor callbacks, PreparationListener listener) {
        long request = requestPreparation();
        try {
            worker.execute(() -> {
                boolean success;
                try { success = known(endpoint); } catch (Exception failure) { success = false; }
                final boolean accepted = success;
                synchronized (this) {
                    if (request != preparationGeneration || !preparing) return;
                    // Claim only this result, not the visible selection. Cancellation before
                    // callback dispatch must not leave a partially prepared endpoint selected.
                }
                try {
                    callbacks.execute(() -> {
                        synchronized (ConnectionCoordinator.this) {
                            if (request != preparationGeneration || !preparing) return;
                            preparing = false;
                            if (accepted) { selected = endpoint; invitation = null; listener.prepared(); }
                            else listener.failed();
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    synchronized (this) { if (request == preparationGeneration) cancelPreparation(); }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            synchronized (this) { if (request == preparationGeneration) cancelPreparation(); }
            throw failure;
        }
        return new Preparation(this, request);
    }
    public ConnectionCoordinator(AuthorizationRepository repository) { this(repository, new SecureRandom()); }
    public ConnectionCoordinator(AuthorizationRepository repository, SecureRandom random) { this.repository = repository; this.random = random; }
    public synchronized boolean canScan() { return idle() && !preparing; }
    public synchronized void setUtteranceActive(boolean value) { utterance = value; }
    public synchronized void selectInvitation(String uri) {
        cancelPreparation();
        if (!idle()) throw new IllegalStateException("Disconnect and finish input before scanning");
        Invitation parsed = Invitation.parse(uri);
        selected = new AuthorizationRepository.Endpoint(parsed.channel, parsed.target, parsed.fingerprint); invitation = parsed;
    }
    public void selectResume(AuthorizationRepository.Endpoint endpoint) throws Exception {
        long request = requestPreparation();
        try {
            boolean known = known(endpoint);
            synchronized (this) {
                if (request != preparationGeneration) throw new java.util.concurrent.CancellationException();
                preparing = false;
                if (!known) throw new IllegalArgumentException("No stored authorization");
                selected = endpoint; invitation = null;
            }
        } catch (Exception failure) {
            synchronized (this) { if (request == preparationGeneration) cancelPreparation(); }
            throw failure;
        }
    }
    private static boolean same(AuthorizationRepository.Endpoint a, AuthorizationRepository.Endpoint b) {
        return a != null && b != null && a.channel.equals(b.channel) && a.target.equals(b.target) && a.fingerprint.equals(b.fingerprint);
    }
    public synchronized AuthorizationRepository.Endpoint selected() { return selected; }
    public synchronized String begin() throws Exception {
        if (preparing || waitingForBond || attempting || ready || utterance || pendingId != null || selected == null) throw new IllegalStateException("Connection busy or no invitation");
        if (invitation != null) {
            AuthorizationRepository.Endpoint pending = repository.pending();
            if (pending != null && !pending.fingerprint.equals(selected.fingerprint))
                throw new IllegalArgumentException("Different Receiver; existing authorization retained");
            for (AuthorizationRepository.Endpoint active : repository.active())
                if (!active.fingerprint.equals(selected.fingerprint))
                    throw new IllegalArgumentException("Different Receiver; existing authorization retained");
        }
        session = new AuthorizationProtocol.Session(repository, selected, invitation, random);
        if (invitation != null) repository.savePending(selected); // No secret persisted, before any hello leaves device.
        invitation = null; attempting = true; sentIds.clear();
        return session.hello();
    }
    public synchronized Result receive(String wire) throws Exception {
        try {
            Map<String, Object> f = StrictJson.object(wire);
            if (StrictJson.integer(f, "v") != 1) throw StrictJson.invalid();
            String type = StrictJson.string(f, "type");
            if (attempting && "challenge".equals(type)) return new Result(Event.PROOF, session.challenge(f), null, null);
            if (attempting && "authorized".equals(type)) {
                session.authorized(f); attempting = false; ready = true;
                return new Result(Event.AUTHORIZED, null, null, null);
            }
            if (attempting && "error".equals(type)) {
                StrictJson.keys(f, "v", "type", "code"); String code = code(f);
                attempting = false; session = null;
                return new Result(Event.REJECTED, null, null, code);
            }
            if (ready && pendingId != null && ("pasted".equals(type) || "error".equals(type))) {
                StrictJson.keys(f, "pasted".equals(type) ? new String[]{"v", "type", "id"} : new String[]{"v", "type", "id", "code"});
                String id = StrictJson.string(f, "id"); if (!id.equals(pendingId)) throw StrictJson.invalid();
                String code = "error".equals(type) ? code(f) : null;
                pendingId = null;
                if ("wifi".equals(selected.channel) || "session_locked".equals(code)) ready = false;
                return new Result(code == null ? Event.PASTED : Event.REJECTED, null, id, code);
            }
            throw StrictJson.invalid();
        } catch (Exception e) { disconnected(); throw e; }
    }
    private static String code(Map<String, Object> f) {
        String code = StrictJson.string(f, "code");
        if (!code.matches("[a-z][a-z0-9_]{0,127}")) throw StrictJson.invalid(); return code;
    }
    public synchronized String finalText(String id, String text) {
        if (!ready || pendingId != null || sentIds.contains(id)) throw new IllegalStateException("Not ready or repeated final id");
        String frame = ManualTextProtocol.finalText(id, text); sentIds.add(id); pendingId = id; return frame;
    }
    public synchronized void disconnected() {
        cancelPreparation();
        if (waitingForBond) { invitation = null; selected = null; waitingForBond = false; }
        outcomeUnknown |= pendingId != null; pendingId = null; attempting = false; ready = false; session = null;
    }
    public synchronized void cancelInvitation() throws Exception {
        if (ready || pendingId != null) throw new IllegalStateException("Connection busy");
        disconnected(); invitation = null; selected = null; repository.clearPending();
    }
    public synchronized boolean ready() { return ready; }
    public synchronized boolean attempting() { return attempting; }
    public synchronized boolean pending() { return pendingId != null; }
    public synchronized String pendingId() { return pendingId; }
    public synchronized boolean outcomeUnknown() { return outcomeUnknown; }
}
