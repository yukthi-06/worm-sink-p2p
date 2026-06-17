package org.wormsink.signaling;

import java.util.List;

public class SessionState {
    public String code;
    public String offer;
    public String answer;
    public List<CandidateData> candidates;

    public static class CandidateData {
        public String candidate;
        public String sdpMid;
        public int sdpMLineIndex;
    }
}
