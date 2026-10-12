package com.saipramod.gitcontext.answer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks that every [n] marker in an answer points to a context block that was actually provided. */
public final class CitationValidator {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})]");

    public record Result(List<Integer> cited, List<Integer> invalid) {
        public boolean grounded() {
            return !cited.isEmpty() && invalid.isEmpty();
        }
    }

    private CitationValidator() { }

    public static Result validate(String answer, int contextSize) {
        Set<Integer> cited = new LinkedHashSet<>();
        List<Integer> invalid = new ArrayList<>();
        Matcher m = MARKER.matcher(answer == null ? "" : answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= contextSize) {
                cited.add(n);
            } else if (!invalid.contains(n)) {
                invalid.add(n);
            }
        }
        return new Result(new ArrayList<>(cited), invalid);
    }
}
