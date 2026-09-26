package com.payrelay.api.bank.mapping;

import java.util.Map;

/** Reads a dotted path like "data.transferId" out of a parsed JSON response. */
public final class JsonPaths {

    private JsonPaths() {
    }

    public static Object get(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object node = root;
        for (String key : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(key);
        }
        return node;
    }

    public static String getString(Map<String, Object> root, String path) {
        Object v = get(root, path);
        return v == null ? null : v.toString();
    }
}
