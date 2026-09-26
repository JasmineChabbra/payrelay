package com.payrelay.api.bank.mapping;

import java.util.List;

/** The mapping could not be applied. This is a configuration problem, so retrying won't help. */
public class MappingException extends RuntimeException {

    private final List<String> problems;

    public MappingException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
