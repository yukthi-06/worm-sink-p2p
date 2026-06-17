package org.wormsink.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class HumanTransferCodeGenerator {

    private static final List<String> WORDS = new ArrayList<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        try (InputStream is = HumanTransferCodeGenerator.class.getResourceAsStream("/words.txt")) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            WORDS.add(line.toLowerCase());
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fallback handled below
        }
        
        if (WORDS.isEmpty()) {
            // Minimal fallback list just in case
            WORDS.add("apple");
            WORDS.add("river");
            WORDS.add("candle");
            WORDS.add("mountain");
            WORDS.add("forest");
            WORDS.add("ocean");
            WORDS.add("sunset");
            WORDS.add("shadow");
        }
    }

    public static String generateCode() {
        return RANDOM.ints(4, 0, WORDS.size())
                .mapToObj(WORDS::get)
                .collect(Collectors.joining("-"));
    }
}
