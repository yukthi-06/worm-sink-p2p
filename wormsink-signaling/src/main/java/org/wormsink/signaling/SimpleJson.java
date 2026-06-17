package org.wormsink.signaling;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SimpleJson {
    public static String getField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(?:\"([^\"]*)\"|([^,}\\s]+))");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String val = matcher.group(1);
            if (val == null) {
                val = matcher.group(2);
            }
            // JSON null literal → Java null
            if ("null".equals(val)) {
                return null;
            }
            if (val != null) {
                return val.replace("\\\\", "\\")
                          .replace("\\\"", "\"")
                          .replace("\\r", "\r")
                          .replace("\\n", "\n")
                          .replace("\\t", "\t");
            }
        }
        return null;
    }


    public static List<String> getArray(String json, String field) {
        List<String> list = new ArrayList<>();
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String arrayContent = matcher.group(1);
            Pattern itemPattern = Pattern.compile("\\{[^\\}]*\\}");
            Matcher itemMatcher = itemPattern.matcher(arrayContent);
            while (itemMatcher.find()) {
                list.add(itemMatcher.group());
            }
        }
        return list;
    }

    public static String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\r", "\\r")
                  .replace("\n", "\\n")
                  .replace("\t", "\\t");
    }
}
