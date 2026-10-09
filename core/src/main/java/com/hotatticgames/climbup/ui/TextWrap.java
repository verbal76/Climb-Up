package com.hotatticgames.climbup.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Greedy word wrap: lines never exceed maxWidth unless a single word is wider than a line (then it is broken). Pure, so it is unit-testable without a GL context. */
public final class TextWrap {
    private TextWrap() {}

    public static List<String> wrap(String text, ToDoubleFunction<String> width, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            while (width.applyAsDouble(word) > maxWidth && word.length() > 1) {          // a word wider than the box: break it rather than overflow
                int n = word.length() - 1; while (n > 1 && width.applyAsDouble(word.substring(0, n)) > maxWidth) n--;
                if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); }
                lines.add(word.substring(0, n)); word = word.substring(n);
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && width.applyAsDouble(candidate) > maxWidth) { lines.add(line.toString()); line.setLength(0); line.append(word); }
            else { line.setLength(0); line.append(candidate); }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
