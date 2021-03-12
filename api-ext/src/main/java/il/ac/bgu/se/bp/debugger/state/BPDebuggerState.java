package il.ac.bgu.se.bp.debugger.state;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public class BPDebuggerState implements Serializable {
    private static final long serialVersionUID = 6320377753998745711L;

    private List<BThreadInfo> bThreadInfoList;
    private EventsStatus eventsStatus;
    private EventInfo chosenEvent;

    public BPDebuggerState() {
        this.bThreadInfoList = new ArrayList<>();
        this.eventsStatus = new EventsStatus(new HashSet<>(), new HashSet<>(), new HashSet<>());
        this.chosenEvent = null;
    }

    public BPDebuggerState(List<BThreadInfo> bThreadInfoList, EventsStatus eventsStatus) {
        this.bThreadInfoList = bThreadInfoList;
        this.eventsStatus = eventsStatus;
    }

    public List<BThreadInfo> getbThreadInfoList() {
        return bThreadInfoList;
    }

    public void setbThreadInfoList(List<BThreadInfo> bThreadInfoList) {
        this.bThreadInfoList = bThreadInfoList;
    }

    public EventsStatus getEventsStatus() {
        return eventsStatus;
    }

    public void setEventsStatus(EventsStatus eventsStatus) {
        this.eventsStatus = eventsStatus;
    }

    public EventInfo getChosenEvent() {
        return chosenEvent;
    }

    public void setChosenEvent(EventInfo chosenEvent) {
        this.chosenEvent = chosenEvent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BPDebuggerState that = (BPDebuggerState) o;
        return bThreadInfoList.equals(that.bThreadInfoList) &&
                Objects.equals(eventsStatus, that.eventsStatus) &&
                Objects.equals(chosenEvent, that.chosenEvent);}

    @Override
    public int hashCode() {
        return Objects.hash(bThreadInfoList, eventsStatus, chosenEvent);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("BPDebuggerState\n");
        for(BThreadInfo b : bThreadInfoList)
            s.append("["+ b.toString()+"],\n\n");
        s.append(eventsStatus.toString()+ "\n");
        return s.toString();
    }
}
