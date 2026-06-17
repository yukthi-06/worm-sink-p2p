package org.wormsink.transfer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ResumeEngine {

    public static class TransferState {
        public String transferId;
        public String fileHash;
        public String fileName;
        public long fileSize;
        public int totalChunks;
        public String destinationPath;
        public final Set<Integer> completedChunks = Collections.synchronizedSet(new HashSet<>());
    }

    public static void saveState(TransferState state, String stateFilePath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("transferId=").append(state.transferId).append("\n");
        sb.append("fileHash=").append(state.fileHash).append("\n");
        sb.append("fileName=").append(state.fileName).append("\n");
        sb.append("fileSize=").append(state.fileSize).append("\n");
        sb.append("totalChunks=").append(state.totalChunks).append("\n");
        sb.append("destinationPath=").append(state.destinationPath).append("\n");
        
        String completedStr;
        synchronized (state.completedChunks) {
            completedStr = state.completedChunks.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
        }
        sb.append("completedChunks=").append(completedStr).append("\n");

        Files.writeString(Paths.get(stateFilePath), sb.toString(), StandardCharsets.UTF_8);
    }

    public static TransferState loadState(String stateFilePath) throws IOException {
        if (!Files.exists(Paths.get(stateFilePath))) {
            return null;
        }
        List<String> lines = Files.readAllLines(Paths.get(stateFilePath), StandardCharsets.UTF_8);
        TransferState state = new TransferState();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int idx = line.indexOf('=');
            if (idx == -1) continue;
            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();
            switch (key) {
                case "transferId":
                    state.transferId = val;
                    break;
                case "fileHash":
                    state.fileHash = val;
                    break;
                case "fileName":
                    state.fileName = val;
                    break;
                case "fileSize":
                    state.fileSize = Long.parseLong(val);
                    break;
                case "totalChunks":
                    state.totalChunks = Integer.parseInt(val);
                    break;
                case "destinationPath":
                    state.destinationPath = val;
                    break;
                case "completedChunks":
                    if (!val.isEmpty()) {
                        for (String chunkStr : val.split(",")) {
                            state.completedChunks.add(Integer.parseInt(chunkStr.trim()));
                        }
                    }
                    break;
            }
        }
        return state;
    }
}
