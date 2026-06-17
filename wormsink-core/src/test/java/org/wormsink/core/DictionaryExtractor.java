package org.wormsink.core;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DictionaryExtractor {

    @Test
    public void extractDictionary() throws IOException {
        String sourcePath = "C:/Users/ShibuPC/.gemini/antigravity-ide/brain/22effc07-82ce-47aa-8aaf-9a7adb3e6397/.system_generated/steps/63/content.md";
        String targetDir = "x:/Projects_X/0_Active/1_Java_Active/AI_Assisted/WormSink_Java_GITHUB/wormsink-core/src/main/resources";
        String targetPath = targetDir + "/words.txt";

        Files.createDirectories(Paths.get(targetDir));

        try (BufferedReader reader = new BufferedReader(new FileReader(sourcePath));
             BufferedWriter writer = new BufferedWriter(new FileWriter(targetPath))) {

            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Match 5 digits followed by whitespace and a word
                if (line.matches("^\\d{5}\\s+[a-z]+$")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length == 2) {
                        writer.write(parts[1]);
                        writer.newLine();
                        count++;
                    }
                }
            }
            System.out.println("Extracted " + count + " words into " + targetPath);
        }
    }
}
