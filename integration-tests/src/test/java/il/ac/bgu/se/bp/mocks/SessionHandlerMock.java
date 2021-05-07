package il.ac.bgu.se.bp.mocks;

import il.ac.bgu.se.bp.service.manage.SessionHandlerImpl;
import il.ac.bgu.se.bp.socket.console.ConsoleMessage;
import il.ac.bgu.se.bp.socket.state.BPDebuggerState;
import il.ac.bgu.se.bp.socket.status.ProgramStatus;
import il.ac.bgu.se.bp.socket.status.Status;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SessionHandlerMock extends SessionHandlerImpl {
    private ConcurrentMap<String, List<BPDebuggerState>> debuggerStatesPerUser = new ConcurrentHashMap<>();
    private ConcurrentMap<String, List<ConsoleMessage>> consoleMessagesPerUser = new ConcurrentHashMap<>();
    private ConcurrentMap<String, Status> usersStatus = new ConcurrentHashMap<>();

    @Override
    public void visit(String userId, BPDebuggerState debuggerState) {
        debuggerStatesPerUser.get(userId).add(debuggerState);
    }

    @Override
    public void visit(String userId, ConsoleMessage consoleMessage) {
        consoleMessagesPerUser.get(userId).add(consoleMessage);
    }

    @Override
    public void visit(String userId, ProgramStatus programStatus) {
        super.visit(userId, programStatus);
        Status userStatus = programStatus.getStatus();
        if (Status.DEBUG.equals(userStatus)) {
            debuggerStatesPerUser.put(userId, new LinkedList<>());
            consoleMessagesPerUser.put(userId, new LinkedList<>());
        }
        else if (Status.STOP.equals(userStatus)) {
            debuggerStatesPerUser.remove(userId);
            consoleMessagesPerUser.remove(userId);
        }
        usersStatus.put(userId, userStatus);
    }

    public Boolean isUserFinishedRunning(String userId) {
        return !debuggerStatesPerUser.containsKey(userId) && !consoleMessagesPerUser.containsKey(userId);
    }

    public void cleanMockData() {
        debuggerStatesPerUser.clear();
        consoleMessagesPerUser.clear();
    }

    public void cleanUserMockData(String userId) {
        debuggerStatesPerUser.getOrDefault(userId, new LinkedList<>()).clear();
        consoleMessagesPerUser.getOrDefault(userId, new LinkedList<>()).clear();
    }


    public List<BPDebuggerState> getUsersDebuggerStates(String userId) {
        return debuggerStatesPerUser.get(userId);
    }

    public BPDebuggerState getUsersLastDebuggerState(String userId) {
        return getLastIfNotEmpty(getUsersDebuggerStates(userId));
    }

    public Status getUsersStatus(String userId) {
        return usersStatus.get(userId);
    }

    public void removeUsersStatus(String userId) {
        usersStatus.remove(userId);
    }

    public List<ConsoleMessage> getUsersConsoleMessages(String userId) {
        return consoleMessagesPerUser.get(userId);
    }

    public ConsoleMessage getUsersLastConsoleMessage(String userId) {
        return getLastIfNotEmpty(getUsersConsoleMessages(userId));
    }

    private <T> T getLastIfNotEmpty(List<T> list) {
        return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
    }
}
