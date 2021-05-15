package il.ac.bgu.se.bp.execution;

import il.ac.bgu.cs.bp.bpjs.execution.listeners.BProgramRunnerListener;
import il.ac.bgu.cs.bp.bpjs.execution.listeners.PrintBProgramRunnerListener;
import il.ac.bgu.cs.bp.bpjs.internal.ExecutorServiceMaker;
import il.ac.bgu.cs.bp.bpjs.model.*;
import il.ac.bgu.cs.bp.bpjs.model.eventselection.EventSelectionResult;
import il.ac.bgu.cs.bp.bpjs.model.eventselection.EventSelectionStrategy;
import il.ac.bgu.se.bp.debugger.BPJsDebugger;
import il.ac.bgu.se.bp.debugger.DebuggerLevel;
import il.ac.bgu.se.bp.debugger.RunnerState;
import il.ac.bgu.se.bp.debugger.commands.*;
import il.ac.bgu.se.bp.debugger.engine.DebuggerEngine;
import il.ac.bgu.se.bp.debugger.engine.DebuggerEngineImpl;
import il.ac.bgu.se.bp.debugger.engine.SyncSnapshotHolder;
import il.ac.bgu.se.bp.debugger.engine.SyncSnapshotHolderImpl;
import il.ac.bgu.se.bp.debugger.engine.events.BPConsoleEvent;
import il.ac.bgu.se.bp.debugger.engine.events.ProgramStatusEvent;
import il.ac.bgu.se.bp.debugger.manage.ProgramValidator;
import il.ac.bgu.se.bp.error.ErrorCode;
import il.ac.bgu.se.bp.logger.Logger;
import il.ac.bgu.se.bp.rest.response.BooleanResponse;
import il.ac.bgu.se.bp.rest.response.DebugResponse;
import il.ac.bgu.se.bp.rest.response.GetSyncSnapshotsResponse;
import il.ac.bgu.se.bp.socket.console.ConsoleMessage;
import il.ac.bgu.se.bp.socket.console.LogType;
import il.ac.bgu.se.bp.socket.state.BPDebuggerState;
import il.ac.bgu.se.bp.socket.state.EventInfo;
import il.ac.bgu.se.bp.socket.status.Status;
import il.ac.bgu.se.bp.utils.DebuggerBProgramRunnerListener;
import il.ac.bgu.se.bp.utils.DebuggerPrintStream;
import il.ac.bgu.se.bp.utils.DebuggerStateHelper;
import il.ac.bgu.se.bp.utils.observer.BPEvent;
import il.ac.bgu.se.bp.utils.observer.Subscriber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static il.ac.bgu.se.bp.utils.ProgramStatusHelper.getRunStatusByDebuggerLevel;
import static il.ac.bgu.se.bp.utils.ResponseHelper.createErrorResponse;
import static il.ac.bgu.se.bp.utils.ResponseHelper.createSuccessResponse;
import static java.util.Collections.reverseOrder;

public class BPJsDebuggerImpl implements BPJsDebugger<BooleanResponse> {
    private final static AtomicInteger debuggerThreadIdGenerator = new AtomicInteger(0);
    private Logger logger;

    private String debuggerId;
    private String filename;
    private String debuggerExecutorId;

    private volatile boolean isBProgSetup = false; //indicates if bprog after setup
    private volatile boolean isSetup = false;
    private volatile boolean isStarted = false;
    private volatile boolean isSkipSyncPoints = false;

    private ExecutorService jsExecutorService;
    private ExecutorService bpExecutorService;
    private BProgram bprog;
    private DebuggerEngine<BProgramSyncSnapshot> debuggerEngine;
    private BProgramSyncSnapshot syncSnapshot;
    private int numOfLines;

    private final RunnerState state = new RunnerState();
    private final DebuggerLevel debuggerLevel;
    private final SyncSnapshotHolder<BProgramSyncSnapshot, BEvent> syncSnapshotHolder = new SyncSnapshotHolderImpl();
    private final DebuggerStateHelper debuggerStateHelper;
    private final DebuggerPrintStream debuggerPrintStream = new DebuggerPrintStream();
    private final List<BProgramRunnerListener> listeners = new ArrayList<>();
    private final List<Subscriber<BPEvent>> subscribers = new ArrayList<>();

    @Autowired
    private ProgramValidator<BPJsDebugger> bPjsProgramValidator;

    public BPJsDebuggerImpl(String debuggerId, String filename, DebuggerLevel debuggerLevel) {
        this.debuggerId = debuggerId;
        this.filename = filename;
        this.debuggerLevel = debuggerLevel;
        debuggerStateHelper = new DebuggerStateHelper(this, syncSnapshotHolder, debuggerLevel);
        initDebugger();
    }

    public BPJsDebuggerImpl(String debuggerId, String filename) {
        this(debuggerId, filename, DebuggerLevel.NORMAL);
    }

    private void initDebugger() {
        debuggerExecutorId = "BPJsDebuggerRunner-" + debuggerThreadIdGenerator.incrementAndGet();
        jsExecutorService = ExecutorServiceMaker.makeWithName(debuggerExecutorId);
        bpExecutorService = ExecutorServiceMaker.makeWithName(debuggerExecutorId);
        logger = new Logger(BPJsDebuggerImpl.class, debuggerId);
        debuggerEngine = new DebuggerEngineImpl(debuggerId, filename, state, debuggerStateHelper, debuggerExecutorId);
        debuggerEngine.changeDebuggerLevel(debuggerLevel);
        debuggerPrintStream.setDebuggerId(debuggerId);
        bprog = new ResourceBProgram(filename);
        initListeners(bprog);
    }

    private void initListeners(BProgram bprog) {
        listeners.add(new PrintBProgramRunnerListener(debuggerPrintStream));
        listeners.add(new DebuggerBProgramRunnerListener(debuggerStateHelper));
        bprog.setAddBThreadCallback((bp, bt) -> listeners.forEach(l -> l.bthreadAdded(bp, bt)));
    }

    @Override
    public DebugResponse setup(Map<Integer, Boolean> breakpoints, boolean isSkipBreakpoints, boolean isSkipSyncPoints, boolean isWaitForExternalEvents) {
        logger.info("setup isSkipBreakpoints: {0}, isSkipSyncPoints: {1}, isWaitForExternalEvents: {2}", isSkipSyncPoints, isSkipBreakpoints, isWaitForExternalEvents);
        if (!isBProgSetup) { // may get twice to setup - must do bprog setup first time only
            listeners.forEach(l -> l.starting(bprog));
            bprog.setLoggerOutputStreamer(debuggerPrintStream);
            syncSnapshot = awaitForExecutorServiceToFinishTask(bprog::setup);
            if (syncSnapshot == null) {
                onExit();
                return new DebugResponse(false, ErrorCode.BP_SETUP_FAIL, new boolean[0]);
            }
            syncSnapshot.getBThreadSnapshots().forEach(sn -> listeners.forEach(l -> l.bthreadAdded(bprog, sn)));
            isBProgSetup = true;
            if (syncSnapshot.getFailedAssertion() != null) {
                onExit();
                return new DebugResponse(false, ErrorCode.BP_SETUP_FAIL, new boolean[0]);
            }
        }
        toggleMuteSyncPoints(isSkipSyncPoints);
        debuggerEngine.setupBreakpoints(breakpoints);
        debuggerEngine.toggleMuteBreakpoints(isSkipBreakpoints);

        debuggerEngine.setSyncSnapshot(syncSnapshot);
        setIsSetup(true);
        state.setDebuggerState(RunnerState.State.STOPPED);
        boolean[] actualBreakpoints = debuggerEngine.getBreakpoints();
        numOfLines = actualBreakpoints.length;

        bprog.setWaitForExternalEvents(isWaitForExternalEvents);
        return new DebugResponse(true, actualBreakpoints);
    }

    @Override
    public synchronized BooleanResponse toggleMuteSyncPoints(boolean toggleMuteSyncPoints) {
        logger.info("toggleMuteSyncPoints to: " + toggleMuteSyncPoints);
        this.isSkipSyncPoints = toggleMuteSyncPoints;
        return createSuccessResponse();
    }

    @Override
    public GetSyncSnapshotsResponse getSyncSnapshotsHistory() {
        SortedMap<Long, BPDebuggerState> syncSnapshotsHistory = new TreeMap<>();

        syncSnapshotHolder.getAllSyncSnapshots().forEach((time, bProgramSyncSnapshotBEventPair) -> {
            BPDebuggerState bpDebuggerState = debuggerStateHelper.generateDebuggerState(bProgramSyncSnapshotBEventPair.getLeft(), state, null, null);
            syncSnapshotsHistory.put(time, bpDebuggerState);
        });

        return new GetSyncSnapshotsResponse(syncSnapshotsHistory);
    }

    @Override
    public BooleanResponse setSyncSnapshot(long snapShotTime) {
        logger.info("setSyncSnapshot() snapShotTime: {0}, state: {1}", snapShotTime, state.getDebuggerState().toString());
        if (!RunnerState.State.SYNC_STATE.equals(state.getDebuggerState())) {
            return createErrorResponse(ErrorCode.NOT_IN_BP_SYNC_STATE);
        }
        BProgramSyncSnapshot newSnapshot = syncSnapshotHolder.popKey(snapShotTime);
        if (newSnapshot == null) {
            return createErrorResponse(ErrorCode.CANNOT_REPLACE_SNAPSHOT);
        }

        syncSnapshot = newSnapshot;

        debuggerStateHelper.cleanFields();
        debuggerEngine.setSyncSnapshot(syncSnapshot);
        debuggerEngine.onStateChanged();
        return createSuccessResponse();
    }

    @Override
    public RunnerState getDebuggerState() {
        return state;
    }

    @Override
    public String getDebuggerExecutorId() {
        return debuggerExecutorId;
    }

    @Override
    public SortedMap<Long, EventInfo> getEventsHistory(int from, int to) {
        if (from < 0 || to < 0 || to < from) {
            return null;
        }
        return debuggerStateHelper.generateEventsHistory(from, to);
    }

    private synchronized void setIsStarted(boolean isStarted) {
        this.isStarted = isStarted;
    }

    private synchronized void setIsSetup(boolean isSetup) {
        this.isSetup = isSetup;
    }

    @Override
    public synchronized boolean isSetup() {
        return isSetup;
    }

    @Override
    public synchronized boolean isStarted() {
        return isStarted;
    }

    @Override
    public DebugResponse startSync(Map<Integer, Boolean> breakpointsMap, boolean isSkipSyncPoints, boolean isSkipBreakpoints, boolean isWaitForExternalEvents) {
        notifySubscribers(new ProgramStatusEvent(debuggerId, getRunStatusByDebuggerLevel(debuggerLevel)));
        DebugResponse debugResponse = setup(breakpointsMap, isSkipBreakpoints, isSkipSyncPoints, isWaitForExternalEvents);
        if (debugResponse.isSuccess()) {
            bpExecutorService.execute(this::runStartSync);
        }
        return debugResponse;
    }

    private BooleanResponse runStartSync() {
        try {
            setIsStarted(true);
            listeners.forEach(l -> l.started(bprog));
            syncSnapshot = syncSnapshot.start(jsExecutorService);
            if (!syncSnapshot.isStateValid()) {
                onInvalidStateError("Start sync fatal error");
                onExit();
                return createErrorResponse(ErrorCode.BP_SETUP_FAIL);
            }
            state.setDebuggerState(RunnerState.State.SYNC_STATE);
            debuggerEngine.setSyncSnapshot(syncSnapshot);
            syncSnapshotHolder.addSyncSnapshot(syncSnapshot, null);
            logger.info("~FIRST SYNC STATE~");
            if (isSkipSyncPoints) {
                nextSync();
            }
            else {
                logger.debug("Generate state from startSync");
                debuggerEngine.onStateChanged();
                notifySubscribers(new ProgramStatusEvent(debuggerId, Status.SYNCSTATE));
            }
        } catch (InterruptedException e) {
            if (debuggerEngine.isRunning()) {
                logger.warning("got InterruptedException in startSync");
                onExit();
                return createErrorResponse(ErrorCode.BP_SETUP_FAIL);
            }
        } catch (RejectedExecutionException e) {
            logger.error("Forced to stop");
            onExit();
            return createErrorResponse(ErrorCode.BP_SETUP_FAIL);
        } catch (Exception e) {
            logger.error("runStartSync failed, error: {0}", e.getMessage());
            notifySubscribers(new BPConsoleEvent(debuggerId, new ConsoleMessage(e.getMessage(), LogType.error)));
        }
        return createSuccessResponse();
    }

    private void onInvalidStateError(String error) {
        FailedAssertion failedAssertion = syncSnapshot.getFailedAssertion();
        listeners.forEach(l -> l.assertionFailed(bprog, failedAssertion));
        state.setDebuggerState(RunnerState.State.STOPPED);
        logger.error(error);
    }

    @Override
    public BooleanResponse nextSync() {
        BooleanResponse booleanResponse = bPjsProgramValidator.validateNextSync(this);
        if (!booleanResponse.isSuccess()) {
            return booleanResponse;
        }

        if (!syncSnapshot.isStateValid()) {
            onInvalidStateError("next sync fatal error");
            return createErrorResponse(ErrorCode.INVALID_SYNC_SNAPSHOT_STATE);
        }

//        asyncOperationRunner.runAsyncCallback(this::runNextSync);
        bpExecutorService.execute(this::runNextSync);
        return createSuccessResponse();
    }

    private boolean runNextSync() {
        logger.info("runNextSync state: {0}", state.getDebuggerState());

        if (!isThereAnyPossibleEvents()){
            if (!bprog.isWaitForExternalEvents()) {
                debuggerEngine.onStateChanged();
                logger.info("Event queue empty, not need to wait to external event. terminating....");
                listeners.forEach(l -> l.ended(bprog));
                onExit();
                return true;
            }
            nextSyncOnNoPossibleEvents();
        }
        state.setDebuggerState(RunnerState.State.RUNNING);
        notifySubscribers(new ProgramStatusEvent(debuggerId, getRunStatusByDebuggerLevel(debuggerLevel)));
        EventSelectionStrategy eventSelectionStrategy = bprog.getEventSelectionStrategy();
        Set<BEvent> possibleEvents = eventSelectionStrategy.selectableEvents(syncSnapshot);

        logger.info("Possible events(internal): " + possibleEvents);
        logger.info("External events: " + syncSnapshot.getExternalEvents());

        try {
            Optional<EventSelectionResult> eventOptional = eventSelectionStrategy.select(syncSnapshot, possibleEvents);
            if (eventOptional.isPresent()) {
                nextSyncOnChosenEvent(eventOptional.get());
            }
            else {
                logger.info("Events queue is empty");
            }
        } catch (InterruptedException e) {
            if (debuggerEngine.isRunning()) {
                logger.error("runNextSync: got InterruptedException in nextSync");
                return false;
            }
        } catch (Exception e) {
            logger.error("runNextSync failed, error: {0}", e.getMessage());
            notifySubscribers(new BPConsoleEvent(debuggerId, new ConsoleMessage(e.getMessage(), LogType.error)));
        }
        return true;
    }

    private boolean isThereAnyPossibleEvents(){
        EventSelectionStrategy eventSelectionStrategy = bprog.getEventSelectionStrategy();
        Set<BEvent> possibleEvents = eventSelectionStrategy.selectableEvents(syncSnapshot);
        return !possibleEvents.isEmpty();
    }

    private void nextSyncOnChosenEvent(EventSelectionResult eventSelectionResult) throws Exception {
        BEvent event = eventSelectionResult.getEvent();
        if (!eventSelectionResult.getIndicesToRemove().isEmpty()) {
            removeExternalEvents(eventSelectionResult);
        }
        logger.info("Triggering event " + event);
        debuggerStateHelper.updateCurrentEvent(event.getName());
        BProgramSyncSnapshot lastSnapshot = syncSnapshot;
        syncSnapshot = syncSnapshot.triggerEvent(event, jsExecutorService, listeners);
        if (!syncSnapshot.isStateValid()) {
            onInvalidStateError("Next Sync fatal error");
            return;
        }
        state.setDebuggerState(RunnerState.State.SYNC_STATE);
        syncSnapshotHolder.addSyncSnapshot(lastSnapshot, event);
        debuggerEngine.setSyncSnapshot(syncSnapshot);
        logger.info("~NEW SYNC STATE~");
        if (isSkipSyncPoints) {
            nextSync();
        }
        else {
            logger.debug("Generate state from nextSync");
            notifySubscribers(new ProgramStatusEvent(debuggerId, Status.SYNCSTATE));
            debuggerEngine.onStateChanged();
        }
    }

    private void nextSyncOnNoPossibleEvents() {
        debuggerEngine.onStateChanged();
        state.setDebuggerState(RunnerState.State.WAITING_FOR_EXTERNAL_EVENT);
        try {
            listeners.forEach(l -> l.superstepDone(bprog));
            BEvent next = bprog.takeExternalEvent(); // and now we wait for external event
            if (next == null) {
                logger.info("Event queue empty, not need to wait to external event. terminating....");
                listeners.forEach(l -> l.ended(bprog));
                onExit();
                return;
            }
            syncSnapshot.getExternalEvents().add(next);
        } catch (Exception e) {
            logger.error("nextSyncOnNoPossibleEvents error: {0}", e.getMessage());
        }
    }

    private void onExit() {
        logger.info("started onExit process");
        debuggerEngine.stop();
        jsExecutorService.shutdownNow();
        bpExecutorService.shutdownNow();
        sleep(1000);
        if (!jsExecutorService.isTerminated()) {
            forceStopDebugger();
        }
        notifySubscribers(new ProgramStatusEvent(debuggerId, Status.STOP));
    }

    private void forceStopDebugger() {
        logger.info("debugger is still running, trying to force stop");
        isSkipSyncPoints = false;
        debuggerEngine.toggleMuteBreakpoints(false);

        for (int i = 0; i < numOfLines; i++) {
            if (debuggerEngine.isBreakpointAllowed(i)) {
                new SetBreakpoint(i, true).applyCommand(debuggerEngine);
            }
        }
    }

    private void sleep(int timeToSleep) {
        try {
            logger.info("sleeping " + timeToSleep / 1000 + " sec");
            Thread.sleep(timeToSleep);
        } catch (InterruptedException e) {}
    }

    private void removeExternalEvents(EventSelectionResult esr) {
        // the event selection affected the external event queue.
        List<BEvent> updatedExternals = new ArrayList<>(syncSnapshot.getExternalEvents());
        esr.getIndicesToRemove().stream().sorted(reverseOrder())
                .forEach(idxObj -> updatedExternals.remove(idxObj.intValue()));
        syncSnapshot = syncSnapshot.copyWith(updatedExternals);
    }

    private <T> T awaitForExecutorServiceToFinishTask(Callable<T> callable) {
        try {
            return jsExecutorService.submit(callable).get();
        } catch (Exception e) {
            logger.error("failed running callable task via executor service, error: {0}", e, e.getMessage());
            notifySubscribers(new BPConsoleEvent(debuggerId, new ConsoleMessage(e.getMessage(), LogType.error)));
        }
        return null;
    }

    @Override
    public BooleanResponse continueRun() {
        return bPjsProgramValidator.validateAndRunAsync(this, RunnerState.State.JS_DEBUG,
                createAddCommandCallback(new Continue()));
    }

    @Override
    public BooleanResponse stepInto() {
        return bPjsProgramValidator.validateAndRun(this, RunnerState.State.JS_DEBUG,
                createAddCommandCallback(new StepInto()));
    }

    @Override
    public BooleanResponse stepOver() {
        return bPjsProgramValidator.validateAndRun(this, RunnerState.State.JS_DEBUG,
                createAddCommandCallback(new StepOver()));
    }

    @Override
    public BooleanResponse stepOut() {
        return bPjsProgramValidator.validateAndRun(this, RunnerState.State.JS_DEBUG,
                createAddCommandCallback(new StepOut()));
    }

    @Override
    public BooleanResponse setBreakpoint(final int lineNumber, final boolean stopOnBreakpoint) {
        Callable<BooleanResponse> applyCommandCallback = createApplyCommandCallback(new SetBreakpoint(lineNumber, stopOnBreakpoint), debuggerEngine);
        return bPjsProgramValidator.validateAndRun(this, applyCommandCallback);
    }

    @Override
    public BooleanResponse stop() {
        if (!isSetup()) {
            return createErrorResponse(ErrorCode.SETUP_REQUIRED);
        }
        setIsStarted(false);
        onExit();
        return createSuccessResponse();
    }

    @Override
    public BooleanResponse getState() {
        Callable<BooleanResponse> applyCommandCallback = createApplyCommandCallback(new GetState(), debuggerEngine);
        return bPjsProgramValidator.validateAndRun(this, applyCommandCallback);
    }

    @Override
    public BooleanResponse toggleMuteBreakpoints(boolean toggleBreakPointStatus) {
        Callable<BooleanResponse> applyCommandCallback = createApplyCommandCallback(new ToggleMuteBreakpoints(toggleBreakPointStatus), debuggerEngine);
        return bPjsProgramValidator.validateAndRun(this, applyCommandCallback);
    }

    @Override
    public BooleanResponse addExternalEvent(String externalEvent) {
        if (StringUtils.isEmpty(externalEvent)) {
            return createErrorResponse(ErrorCode.INVALID_EVENT);
        }
        logger.info("Adding external event: {0} , debugger state: {1}",externalEvent, state.getDebuggerState());
        BEvent bEvent = new BEvent(externalEvent);

        if (state.getDebuggerState() == RunnerState.State.WAITING_FOR_EXTERNAL_EVENT){
            bprog.enqueueExternalEvent(bEvent);
        }
        else {
            List<BEvent> updatedExternals = new ArrayList<>(syncSnapshot.getExternalEvents());
            updatedExternals.add(bEvent);
            syncSnapshot = syncSnapshot.copyWith(updatedExternals);
            debuggerEngine.setSyncSnapshot(syncSnapshot);
            debuggerEngine.onStateChanged();
        }
        if (state.getDebuggerState() != RunnerState.State.WAITING_FOR_EXTERNAL_EVENT && state.getDebuggerState() != RunnerState.State.SYNC_STATE )
            bprog.enqueueExternalEvent(bEvent);

        return createSuccessResponse();
    }

    @Override
    public BooleanResponse removeExternalEvent(String externalEvent) {
        if (StringUtils.isEmpty(externalEvent)) {
            return createErrorResponse(ErrorCode.INVALID_EVENT);
        }
        List<BEvent> updatedExternals = new ArrayList<>(syncSnapshot.getExternalEvents());
        updatedExternals.removeIf(bEvent -> bEvent.getName().equals(externalEvent));
        syncSnapshot = syncSnapshot.copyWith(updatedExternals);
        debuggerEngine.setSyncSnapshot(syncSnapshot);
        debuggerEngine.onStateChanged();
        return createSuccessResponse();
    }

    @Override
    public BooleanResponse toggleWaitForExternalEvents(boolean shouldWait) {
        bprog.setWaitForExternalEvents(shouldWait);
        return createSuccessResponse();
    }

    private Callable<BooleanResponse> createAddCommandCallback(DebuggerCommand debuggerCommand) {
        return () -> addCommand(debuggerCommand);
    }

    private Callable<BooleanResponse> createApplyCommandCallback(DebuggerCommand debuggerCommand, DebuggerEngine debugger) {
        return () -> debuggerCommand.applyCommand(debugger);
    }

    private BooleanResponse addCommand(DebuggerCommand debuggerCommand) {
        try {
            debuggerEngine.addCommand(debuggerCommand);
            return createSuccessResponse();
        } catch (Exception e) {
            logger.error("failed adding command: {0}", e, debuggerCommand.toString());
        }
        return createErrorResponse(ErrorCode.FAILED_ADDING_COMMAND);
    }

    @Override
    public void subscribe(Subscriber<BPEvent> subscriber) {
        subscribers.add(subscriber);
        debuggerEngine.subscribe(subscriber);
        debuggerPrintStream.subscribe(subscriber);
    }

    @Override
    public void unsubscribe(Subscriber<BPEvent> subscriber) {
        subscribers.remove(subscriber);
        debuggerEngine.unsubscribe(subscriber);
        debuggerPrintStream.unsubscribe(subscriber);
    }

    @Override
    public void notifySubscribers(BPEvent event) {
        for (Subscriber<BPEvent> subscriber : subscribers) {
            subscriber.update(event);
        }
    }

    public boolean isSkipSyncPoints() {
        return isSkipSyncPoints;
    }

    public boolean isWaitForExternalEvents() {
        return bprog.isWaitForExternalEvents();
    }

    public boolean isMuteBreakPoints() {
        return debuggerEngine.isMuteBreakpoints();
    }

}