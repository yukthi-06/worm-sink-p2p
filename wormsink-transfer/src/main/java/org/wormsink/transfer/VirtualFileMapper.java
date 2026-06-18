package org.wormsink.transfer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public class VirtualFileMapper {

    public static class FileEntry {
        public String relPath;
        public long size;
        public long virtualStart;
        public long virtualEnd;

        public FileEntry(String relPath, long size, long virtualStart) {
            this.relPath = relPath;
            this.size = size;
            this.virtualStart = virtualStart;
            this.virtualEnd = virtualStart + size;
        }
    }

    private final List<FileEntry> entries = new ArrayList<>();
    private final long totalSize;

    public VirtualFileMapper(List<FileEntry> entries) {
        this.entries.addAll(entries);
        long size = 0;
        if (!entries.isEmpty()) {
            size = entries.get(entries.size() - 1).virtualEnd;
        }
        this.totalSize = size;
    }

    public List<FileEntry> getEntries() {
        return entries;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public static class MappedPosition {
        public FileEntry entry;
        public long fileOffset;

        public MappedPosition(FileEntry entry, long fileOffset) {
            this.entry = entry;
            this.fileOffset = fileOffset;
        }
    }

    public MappedPosition mapOffset(long virtualOffset) {
        if (virtualOffset < 0 || virtualOffset >= totalSize) {
            throw new IndexOutOfBoundsException("Virtual offset " + virtualOffset + " is out of bounds [0, " + totalSize + ")");
        }
        for (FileEntry entry : entries) {
            if (virtualOffset >= entry.virtualStart && virtualOffset < entry.virtualEnd) {
                return new MappedPosition(entry, virtualOffset - entry.virtualStart);
            }
        }
        // Fallback for edge cases (e.g. exactly at the end of the last file, which might happen if length is 0 or end-of-file chunk)
        if (!entries.isEmpty() && virtualOffset == totalSize) {
            FileEntry last = entries.get(entries.size() - 1);
            return new MappedPosition(last, last.size);
        }
        throw new IllegalStateException("Offset " + virtualOffset + " could not be mapped");
    }

    public static List<FileEntry> buildFromDirectory(File directory) {
        List<FileEntry> entries = new ArrayList<>();
        List<File> files = new ArrayList<>();
        collectFiles(directory, files);
        files.sort((f1, f2) -> {
            String r1 = getRelativePath(directory, f1);
            String r2 = getRelativePath(directory, f2);
            return r1.compareTo(r2);
        });

        long currentVirtualOffset = 0;
        for (File file : files) {
            String relPath = getRelativePath(directory, file);
            entries.add(new FileEntry(relPath, file.length(), currentVirtualOffset));
            currentVirtualOffset += file.length();
        }
        return entries;
    }

    private static void collectFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, files);
            } else if (child.isFile()) {
                files.add(child);
            }
        }
    }

    public static String getRelativePath(File base, File file) {
        return base.toURI().relativize(file.toURI()).getPath();
    }

    public static byte[] readVirtualBytes(File baseDir, VirtualFileMapper mapper, long virtualOffset, int length) throws IOException {
        byte[] buffer = new byte[length];
        int bytesRead = 0;
        long currentOffset = virtualOffset;
        while (bytesRead < length) {
            if (currentOffset >= mapper.getTotalSize()) {
                break;
            }
            MappedPosition pos = mapper.mapOffset(currentOffset);
            File physicalFile = new File(baseDir, pos.entry.relPath);
            long bytesToReadThisFile = Math.min(length - bytesRead, pos.entry.size - pos.fileOffset);

            if (bytesToReadThisFile <= 0) {
                // Should not happen unless it's a 0-length file or at EOF
                break;
            }

            try (RandomAccessFile raf = new RandomAccessFile(physicalFile, "r")) {
                raf.seek(pos.fileOffset);
                int read = raf.read(buffer, bytesRead, (int) bytesToReadThisFile);
                if (read <= 0) {
                    break;
                }
                bytesRead += read;
                currentOffset += read;
            }
        }
        if (bytesRead < length) {
            byte[] trimmed = new byte[bytesRead];
            System.arraycopy(buffer, 0, trimmed, 0, bytesRead);
            return trimmed;
        }
        return buffer;
    }

    public static void writeVirtualBytes(File baseDir, VirtualFileMapper mapper, long virtualOffset, byte[] data) throws IOException {
        int bytesWritten = 0;
        long currentOffset = virtualOffset;
        while (bytesWritten < data.length) {
            if (currentOffset >= mapper.getTotalSize()) {
                break;
            }
            MappedPosition pos = mapper.mapOffset(currentOffset);
            File physicalFile = new File(baseDir, pos.entry.relPath);

            File parent = physicalFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            long bytesToWriteThisFile = Math.min(data.length - bytesWritten, pos.entry.size - pos.fileOffset);
            if (bytesToWriteThisFile <= 0) {
                break;
            }

            try (RandomAccessFile raf = new RandomAccessFile(physicalFile, "rw")) {
                raf.seek(pos.fileOffset);
                raf.write(data, bytesWritten, (int) bytesToWriteThisFile);
                bytesWritten += (int) bytesToWriteThisFile;
                currentOffset += bytesToWriteThisFile;
            }
        }
    }

    public static void preallocateFiles(File baseDir, VirtualFileMapper mapper) throws IOException {
        for (FileEntry entry : mapper.getEntries()) {
            File file = new File(baseDir, entry.relPath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(entry.size);
            }
        }
    }
}
