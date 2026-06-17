package org.wormsink.cli;

import org.wormsink.core.*;
import org.wormsink.transfer.TransferEngine;
import org.wormsink.signaling.SignalingClient;

import java.io.File;

public class WormSinkCli {

    private static final String DEFAULT_SIGNALING_URL = "https://wormsinks.shibupc.workers.dev";

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        String target = args[1];

        // Parse optional arguments
        String signalingUrl = DEFAULT_SIGNALING_URL;
        int parallel = 4;
        String outputPath = null;

        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--signaling-url=")) {
                signalingUrl = args[i].substring("--signaling-url=".length());
            } else if (args[i].startsWith("--parallel=")) {
                parallel = Integer.parseInt(args[i].substring("--parallel=".length()));
            } else if (args[i].startsWith("--output=")) {
                outputPath = args[i].substring("--output=".length());
            }
        }

        try {
            if ("send".equalsIgnoreCase(command)) {
                handleSend(target, signalingUrl, parallel);
            } else if ("receive".equalsIgnoreCase(command)) {
                handleReceive(target, signalingUrl, outputPath, parallel);
            } else {
                System.out.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("\nError: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void handleSend(String filePath, String signalingUrl, int parallel) throws Exception {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a file: " + filePath);
        }

        System.out.println("Preparing file...");
        System.out.println("Generating metadata...");

        // Generate transfer code
        SignalingClient sc = new SignalingClient(signalingUrl);
        String code = sc.createSession();

        System.out.println("Transfer code:\n");
        System.out.println("  " + code);
        System.out.println("\nWaiting for receiver...");

        TransferEngine engine = new TransferEngine();
        engine.setParallel(parallel);

        engine.send(file, signalingUrl, code,
                // Progress Listener
                (transferred, total, rate, eta) -> {
                    System.out.print(String.format("\rSent: %s / %s | Rate: %s/s | ETA: %s          ",
                            formatSize(transferred), formatSize(total), formatSize((long) rate), formatDuration(eta)));
                },
                // Connection Listener
                new ConnectionListener() {
                    @Override
                    public void onConnecting() {
                        System.out.println("Connecting to receiver...");
                    }

                    @Override
                    public void onConnected() {
                        System.out.println("\nReceiver connected. Initializing transfer...");
                    }

                    @Override
                    public void onDisconnected() {
                        System.out.println("\nReceiver disconnected.");
                    }
                },
                // Transfer Listener
                new TransferListener() {
                    @Override
                    public void onTransferStarted(String name, long size, String transferId) {
                        System.out.println("Starting transfer of: " + name + " (" + formatSize(size) + ")");
                    }

                    @Override
                    public void onTransferCompleted() {
                        System.out.println("\nTransfer completed successfully!");
                    }

                    @Override
                    public void onTransferFailed(String reason) {
                        System.out.println("\nTransfer failed: " + reason);
                    }

                    @Override
                    public void onTransferResumed(long bytesAlreadyTransferred) {
                        System.out.println("Resuming transfer from: " + formatSize(bytesAlreadyTransferred));
                    }
                });
    }

    private static void handleReceive(String code, String signalingUrl, String outputPath, int parallel) throws Exception {
        if (outputPath == null) {
            // Default to current directory and code name or ask user, but here we default to fileName from metadata
            // Which is handled in onTransferStarted, so we can defer file initialization there or default to code name
            outputPath = ".";  // Default to current directory; TransferEngine resolves actual filename from metadata
        }

        System.out.println("Connecting...");
        System.out.println("Verifying metadata...");

        TransferEngine engine = new TransferEngine();
        engine.setParallel(parallel);

        final String targetPath = outputPath;

        engine.receive(code, targetPath, signalingUrl,
                // Progress Listener
                (transferred, total, rate, eta) -> {
                    System.out.print(String.format("\rDownloaded: %s / %s | Rate: %s/s | ETA: %s          ",
                            formatSize(transferred), formatSize(total), formatSize((long) rate), formatDuration(eta)));
                },
                // Connection Listener
                new ConnectionListener() {
                    @Override
                    public void onConnecting() {
                        System.out.println("Connecting to peer...");
                    }

                    @Override
                    public void onConnected() {
                        System.out.println("\nPeer connected. Fetching file...");
                    }

                    @Override
                    public void onDisconnected() {
                        System.out.println("\nPeer disconnected.");
                    }
                },
                // Transfer Listener
                new TransferListener() {
                    @Override
                    public void onTransferStarted(String name, long size, String transferId) {
                        System.out.println("Downloading: " + name + " (" + formatSize(size) + ")");
                        // Compute the resolved save path the same way TransferEngine does:
                        // if targetPath is a directory, the file goes inside it with the real name.
                        String savePath = new File(targetPath).isDirectory()
                                ? new File(targetPath, name).getPath()
                                : targetPath;
                        System.out.println("Saving to: " + savePath);
                    }

                    @Override
                    public void onTransferCompleted() {
                        System.out.println("\nDownload completed successfully!");
                    }

                    @Override
                    public void onTransferFailed(String reason) {
                        System.out.println("\nDownload failed: " + reason);
                    }

                    @Override
                    public void onTransferResumed(long bytesAlreadyTransferred) {
                        System.out.println("Resuming download from: " + formatSize(bytesAlreadyTransferred));
                    }
                });
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }

    private static String formatDuration(long seconds) {
        if (seconds >= 3600) {
            return String.format("%dh %dm %ds", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        } else if (seconds >= 60) {
            return String.format("%dm %ds", seconds / 60, seconds % 60);
        } else {
            return seconds + "s";
        }
    }

    private static void printUsage() {
        System.out.println("WormSink - Large File P2P Transfer Tool");
        System.out.println("Usage:");
        System.out.println("  wormsink send <file_path> [--signaling-url=<url>] [--parallel=<count>]");
        System.out.println("  wormsink receive <transfer_code> [--signaling-url=<url>] [--output=<save_path>] [--parallel=<count>]");
    }
}
