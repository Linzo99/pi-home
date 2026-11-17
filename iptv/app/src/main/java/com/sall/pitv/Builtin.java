package com.sall.pitv;

import java.io.IOException;

/**
 * Add-ons that live inside Pi TV: their builtin://name/… addresses are answered here, without
 * any server, with the same JSON a Stremio add-on would send.
 */
final class Builtin {
    static final String SCHEME = "builtin://";

    static boolean is(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    /** builtin://name/path → that add-on's answer for /path */
    static String answer(String url) throws IOException {
        String rest = url.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash), path = slash < 0 ? "/" : rest.substring(slash);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (name.equals("erome")) return Erome.answer(path);
        if (name.equals("tasty")) return Tasty.answer(path);
        throw new IOException("no built-in add-on " + name);
    }

    private Builtin() {
    }
}
