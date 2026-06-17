package org.wormsink.signaling;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SignalingClient {
    private final String baseUrl;
    private final HttpClient client;

    public SignalingClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String createSession() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/create"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to create session: Status " + response.statusCode() + " - " + response.body());
        }
        return SimpleJson.getField(response.body(), "code");
    }

    public void sendOffer(String code, String sdp) throws Exception {
        String json = String.format("{\"code\":\"%s\",\"sdp\":\"%s\"}", 
                SimpleJson.escape(code), SimpleJson.escape(sdp));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/offer"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to send offer: Status " + response.statusCode() + " - " + response.body());
        }
    }

    public void sendAnswer(String code, String sdp) throws Exception {
        String json = String.format("{\"code\":\"%s\",\"sdp\":\"%s\"}", 
                SimpleJson.escape(code), SimpleJson.escape(sdp));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/answer"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to send answer: Status " + response.statusCode() + " - " + response.body());
        }
    }

    public void sendCandidate(String code, String candidate, String sdpMid, int sdpMLineIndex) throws Exception {
        String json = String.format("{\"code\":\"%s\",\"candidate\":\"%s\",\"sdpMid\":\"%s\",\"sdpMLineIndex\":%d}", 
                SimpleJson.escape(code), SimpleJson.escape(candidate), SimpleJson.escape(sdpMid), sdpMLineIndex);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/candidate"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to send candidate: Status " + response.statusCode() + " - " + response.body());
        }
    }

    public SessionState getSession(String code) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session/" + code))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        
        String body = response.body();
        SessionState state = new SessionState();
        state.code = SimpleJson.getField(body, "code");
        state.offer = SimpleJson.getField(body, "offer");
        state.answer = SimpleJson.getField(body, "answer");
        
        List<String> candList = SimpleJson.getArray(body, "candidates");
        state.candidates = new ArrayList<>();
        for (String cJson : candList) {
            SessionState.CandidateData cd = new SessionState.CandidateData();
            cd.candidate = SimpleJson.getField(cJson, "candidate");
            cd.sdpMid = SimpleJson.getField(cJson, "sdpMid");
            String idxStr = SimpleJson.getField(cJson, "sdpMLineIndex");
            if (idxStr == null) {
                Pattern numPat = Pattern.compile("\"sdpMLineIndex\"\\s*:\\s*(\\d+)");
                Matcher m = numPat.matcher(cJson);
                if (m.find()) {
                    cd.sdpMLineIndex = Integer.parseInt(m.group(1));
                }
            } else {
                cd.sdpMLineIndex = Integer.parseInt(idxStr);
            }
            state.candidates.add(cd);
        }
        return state;
    }
}
